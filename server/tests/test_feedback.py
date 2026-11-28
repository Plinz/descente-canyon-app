from __future__ import annotations

import dataclasses

import pytest
from fastapi.testclient import TestClient

from dcserver.admin.app import create_admin_app
from dcserver.api import create_app
from dcserver.canyons.service import CanyonService, ProposalError
from dcserver.feedback import service as feedback_module
from dcserver.feedback.service import FeedbackError, FeedbackService
from dcserver.ratelimit import Quota, RateLimiter

from .conftest import new_install_key

TOKEN = "t" * 32


class Clock:
    def __init__(self) -> None:
        self.now = 1_790_000_000.0

    def __call__(self) -> float:
        return self.now


def message(**overrides) -> dict:
    body = {
        "kind": "BUG",
        "text": "La carte reste blanche\r\nquand je reviens de l'onglet Sorties.\x07",
        "technical": {"appVersion": "3.2.1", "versionCode": 30201, "android": "14 (API 34)", "device": "Google Pixel 5", "language": "fr-FR", "other": "x"},
        "contact": {"username": "Canyoniste", "email": "someone@example.org"},
    }
    body.update(overrides)
    return body


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def service(settings, clock) -> FeedbackService:
    feedback = FeedbackService(settings.database_path, settings.pepper, clock=clock)
    yield feedback
    feedback.close()


def test_a_message_is_kept_cleaned_and_its_author_sees_only_what_concerns_them(service):
    key = new_install_key()
    sent = service.submit(message(), key, "203.0.113.5")
    assert sent["kind"] == "BUG"
    assert sent["status"] == "NEW"
    # Line ends unified, the bell character dropped.
    assert sent["text"] == "La carte reste blanche\nquand je reviens de l'onglet Sorties."
    assert sent["reply"] is None
    # The author's view never carries the contact, the keys or the technical details.
    assert set(sent) == {"id", "kind", "text", "status", "createdAt", "updatedAt", "reply", "repliedAt"}

    full = service.get(sent["id"])
    assert full["contact"] == {"username": "Canyoniste", "email": "someone@example.org"}
    # Only the known technical fields are kept.
    assert full["technical"] == {"appVersion": "3.2.1", "versionCode": "30201", "android": "14 (API 34)", "device": "Google Pixel 5", "language": "fr-FR"}

    assert [m["id"] for m in service.mine(key)["feedback"]] == [sent["id"]]
    assert service.mine(new_install_key())["feedback"] == []


def test_an_anonymous_message_without_technical_details_is_accepted(service):
    sent = service.submit({"kind": "IDEA", "text": "Un mode hors ligne pour les cartes IGN."}, new_install_key(), "203.0.113.5")
    full = service.get(sent["id"])
    assert full["contact"] is None
    assert full["technical"] is None


@pytest.mark.parametrize(
    ("body", "code", "field"),
    [
        ({"kind": "RANT", "text": "Pas content du tout."}, "BAD_KIND", "kind"),
        ({"kind": "BUG", "text": "  court  "}, "TEXT_TOO_SHORT", "text"),
        ({"kind": "BUG", "text": "x" * 4001}, "TEXT_TOO_LONG", "text"),
        ({"kind": "BUG", "text": 42}, "BAD_REQUEST", "text"),
        ({"kind": "BUG", "text": "Assez long pour passer.", "contact": {"email": "pas-un-email"}}, "BAD_EMAIL", "contact"),
        ({"kind": "BUG", "text": "Assez long pour passer.", "technical": ["3.2.1"]}, "BAD_REQUEST", "technical"),
    ],
)
def test_a_message_out_of_its_bounds_is_refused_with_the_field(service, body, code, field):
    with pytest.raises(FeedbackError) as error:
        service.submit(body, new_install_key(), "203.0.113.5")
    assert error.value.code == code
    assert error.value.field == field


def test_a_message_needs_a_well_formed_installation_key(service):
    with pytest.raises(FeedbackError) as error:
        service.submit(message(), "not-a-key", "203.0.113.5")
    assert error.value.code == "MISSING_KEY"


def test_one_installation_sends_a_bounded_number_of_messages_a_day(settings, clock):
    limiter = RateLimiter({"feedback:key": Quota(2), "feedback:net": Quota(10)})
    service = FeedbackService(settings.database_path, settings.pepper, limiter=limiter, clock=clock)
    key = new_install_key()
    service.submit(message(), key, "203.0.113.5")
    service.submit(message(), key, "203.0.113.5")
    with pytest.raises(FeedbackError) as error:
        service.submit(message(), key, "203.0.113.5")
    assert error.value.code == "RATE_LIMITED"
    clock.now += 24 * 3600 + 1
    service.submit(message(), key, "203.0.113.5")
    service.close()


def test_the_reply_and_the_status_reach_the_author(service, clock):
    key = new_install_key()
    sent = service.submit(message(), key, "203.0.113.5")
    clock.now += 3600
    service.update(sent["id"], reply="Merci, corrigé dans la prochaine version.")
    mine = service.mine(key)["feedback"][0]
    # A reply to a new message marks it read.
    assert mine["status"] == "READ"
    assert mine["reply"] == "Merci, corrigé dans la prochaine version."
    assert mine["repliedAt"] is not None
    replied_at = mine["repliedAt"]

    clock.now += 3600
    service.update(sent["id"], status="DONE", reply="Merci, corrigé dans la prochaine version.")
    mine = service.mine(key)["feedback"][0]
    assert mine["status"] == "DONE"
    # The same reply keeps its date.
    assert mine["repliedAt"] == replied_at

    service.update(sent["id"], reply="")
    assert service.mine(key)["feedback"][0]["reply"] is None

    with pytest.raises(FeedbackError):
        service.update(sent["id"], status="LOST")


def test_the_author_deletes_their_own_message_only(service):
    key = new_install_key()
    sent = service.submit(message(), key, "203.0.113.5")
    with pytest.raises(FeedbackError) as error:
        service.delete_mine(sent["id"], new_install_key())
    assert error.value.code == "NOT_AUTHOR"
    service.delete_mine(sent["id"], key)
    assert service.mine(key)["feedback"] == []
    with pytest.raises(FeedbackError) as error:
        service.get(sent["id"])
    assert error.value.code == "NOT_FOUND"


def test_a_blocked_author_is_refused_and_their_open_messages_are_declined(settings, service):
    key = new_install_key()
    first = service.submit(message(), key, "203.0.113.5")
    service.submit(message(kind="IDEA"), key, "203.0.113.5")
    assert service.block_author(first["id"]) == 2
    assert {m["status"] for m in service.mine(key)["feedback"]} == {"DECLINED"}
    with pytest.raises(FeedbackError) as error:
        service.submit(message(), key, "203.0.113.5")
    assert error.value.code == "BLOCKED"
    # The same list as the canyon proposals: blocked there too.
    canyons = CanyonService(settings.database_path, settings.pepper)
    sheet = {"nom": "Ravin bloqué", "descente": "Une C20.", "points": [{"type": "ENTREE", "latitude": 45.1, "longitude": 5.7}]}
    with pytest.raises(ProposalError) as refused:
        canyons.submit({"kind": "NEW", "licence": "CC0-1.0", "content": sheet}, key, "203.0.113.5")
    assert refused.value.code == "BLOCKED"
    canyons.close()
    assert service.unblock_author(first["id"])
    service.submit(message(), key, "203.0.113.5")


def test_closed_messages_are_deleted_a_year_after_they_were_closed(service, clock):
    key = new_install_key()
    done = service.submit(message(), key, "203.0.113.5")
    still_open = service.submit(message(kind="IDEA"), key, "203.0.113.5")
    service.update(done["id"], status="DONE")
    clock.now += feedback_module.RETENTION_S - 60
    assert service.purge() == 0
    clock.now += 120
    assert service.purge() == 1
    assert [m["id"] for m in service.mine(key)["feedback"]] == [still_open["id"]]


def test_the_review_list_splits_open_and_closed_messages(service):
    key = new_install_key()
    bug = service.submit(message(), key, "203.0.113.5")
    idea = service.submit(message(kind="IDEA", text="Afficher la météo de la semaine."), key, "203.0.113.5")
    service.update(idea["id"], status="DECLINED")
    assert [m["id"] for m in service.messages("OPEN")] == [bug["id"]]
    assert [m["id"] for m in service.messages("CLOSED")] == [idea["id"]]
    assert service.messages("OPEN")[0]["excerpt"] == "La carte reste blanche"
    assert service.new_count() == 1


def test_the_api_sends_lists_and_deletes_messages(settings):
    key = new_install_key()
    headers = {"X-Install-Key": key}
    with TestClient(create_app(settings)) as client:
        sent = client.post("/v1/feedback", json=message(), headers=headers)
        assert sent.status_code == 201, sent.text
        refused = client.post("/v1/feedback", json=message(text="court"), headers=headers)
        assert refused.status_code == 422
        assert refused.json() == {"error": "TEXT_TOO_SHORT", "field": "text"}
        assert client.post("/v1/feedback", json=message()).status_code == 401
        assert client.post("/v1/feedback", content=b"x" * (40 * 1024), headers=headers).status_code == 413
        mine = client.get("/v1/feedback/mine", headers=headers)
        assert mine.status_code == 200
        assert [m["id"] for m in mine.json()["feedback"]] == [sent.json()["id"]]
        assert "contact" not in mine.text
        assert client.delete(f"/v1/feedback/{sent.json()['id']}", headers=headers).status_code == 204
        assert client.get("/v1/feedback/mine", headers=headers).json() == {"feedback": []}


def test_the_review_tool_reads_answers_and_closes_messages(settings):
    settings = dataclasses.replace(settings, admin_token=TOKEN)
    key = new_install_key()
    with TestClient(create_app(settings)) as public:
        sent = public.post("/v1/feedback", json=message(), headers={"X-Install-Key": key}).json()
    admin_headers = {"X-Admin-Token": TOKEN, "Host": "localhost"}
    with TestClient(create_admin_app(settings), base_url="http://localhost") as admin:
        assert admin.get("/api/feedback", headers={"Host": "localhost"}).status_code == 401
        assert admin.get("/api/status", headers=admin_headers).json()["newFeedback"] == 1
        listed = admin.get("/api/feedback", headers=admin_headers).json()
        assert [m["id"] for m in listed] == [sent["id"]]
        detail = admin.get(f"/api/feedback/{sent['id']}", headers=admin_headers).json()
        assert detail["contact"]["email"] == "someone@example.org"
        answered = admin.post(f"/api/feedback/{sent['id']}", json={"status": "PLANNED", "reply": "Bonne idée, prévu."}, headers=admin_headers)
        assert answered.status_code == 200
        assert answered.json()["status"] == "PLANNED"
        assert admin.post(f"/api/feedback/{sent['id']}", json={"status": "NOPE"}, headers=admin_headers).status_code == 400
        assert admin.post(f"/api/feedback/{sent['id']}/block", headers=admin_headers).json() == {"declined": 1}
        assert admin.post(f"/api/feedback/{sent['id']}/unblock", headers=admin_headers).json() == {"unblocked": True}
        assert admin.delete(f"/api/feedback/{sent['id']}", headers=admin_headers).status_code == 204
        assert admin.get(f"/api/feedback/{sent['id']}", headers=admin_headers).status_code == 404
