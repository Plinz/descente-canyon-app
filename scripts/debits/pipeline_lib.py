from __future__ import annotations

import hashlib
import json
import math
import re
import time
import unicodedata
from json import JSONDecodeError
from datetime import date, datetime, time as datetime_time, timedelta
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE_URL = "https://www.descente-canyon.com"
OPEN_METEO_ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
DEFAULT_USER_AGENT = "DescenteCanyonDebitPipeline/0.1"
DEFAULT_ASSUMED_OBSERVATION_HOUR = 8
EARTH_RADIUS_KM = 6371.0088
WATERSHED_DESCRIPTOR_STATUS_FLAG_MAP = {
    "descriptorStatus": "descriptorStatusOk",
    "soilDescriptorStatus": "soilDescriptorStatusOk",
    "hydroLakesStatus": "hydroLakesStatusOk",
    "gdwStatus": "gdwStatusOk",
    "climateDescriptorStatus": "climateDescriptorStatusOk",
    "geologyDescriptorStatus": "geologyDescriptorStatusOk",
    "imperviousDescriptorStatus": "imperviousDescriptorStatusOk",
    "glacierDescriptorStatus": "glacierDescriptorStatusOk",
    "osmRegulationStatus": "osmRegulationStatusOk",
}
WATERSHED_DESCRIPTOR_NUMERIC_KEYS = [
    "forcedByReview",
    "watershedCellCount",
    "watershedValidCellCount",
    "watershedNoDataFraction",
    "basinAreaRasterKm2",
    "demResolutionM",
    "watershedQualityScore",
    "basinMinElevationM",
    "basinMeanElevationM",
    "basinMedianElevationM",
    "basinMaxElevationM",
    "basinElevationStdM",
    "basinReliefM",
    "fractionAbove1500m",
    "fractionAbove2000m",
    "fractionAbove2500m",
    "meanSlopeDeg",
    "medianSlopeDeg",
    "p90SlopeDeg",
    "maxSlopeDeg",
    "fractionSlopeOver10Deg",
    "fractionSlopeOver20Deg",
    "fractionSlopeOver30Deg",
    "aspectNorthFraction",
    "aspectEastFraction",
    "aspectSouthFraction",
    "aspectWestFraction",
    "terrainRuggednessIndex",
    "maxFlowPathLengthKm",
    "mainFlowLengthKm",
    "mainChannelSlopePercent",
    "timeOfConcentrationKirpichMin",
    "timeOfConcentrationGiandottiMin",
    "meltonRuggedness",
    "outletElevationM",
    "outletSnapDistanceM",
    "streamExtractionThresholdKm2",
    "streamCellCount",
    "streamFrequencyPerKm2",
    "drainageDensityKmPerKm2",
    "streamLinkCount",
    "streamSegmentCount",
    "junctionCount",
    "strahlerOrder",
    "firstOrderLengthFraction",
    "totalStreamLengthKm",
    "meanAnnualPrecipMm",
    "meanMonthlyPrecipSeasonality",
    "meanAnnualTemperatureC",
    "meanWinterTemperatureC",
    "meanSnowFractionClimatology",
    "potentialEvapotranspiration",
    "aridityIndex",
    "continentalityProxy",
    "oceanicityProxy",
    "hypsometricIntegral",
    "topographicWetnessIndexMean",
    "topographicWetnessIndexP90",
    "handMeanM",
    "handMedianM",
    "handP90M",
    "meanPlanCurvature",
    "meanProfileCurvature",
    "valleyConfinementIndex",
    "channelConfinementRatio",
    "flowAccumulationP50Km2",
    "flowAccumulationP90Km2",
    "flowAccumulationP99Km2",
    "landCoverValidFraction",
    "forestFraction",
    "shrubFraction",
    "grassFraction",
    "croplandFraction",
    "urbanFraction",
    "bareRockFraction",
    "snowIceFraction",
    "permanentWaterFraction",
    "wetlandFraction",
    "mangroveFraction",
    "mossLichenFraction",
    "waterPatchCount",
    "wetlandPatchCount",
    "forestPatchCount",
    "urbanPatchCount",
    "largestForestPatchFraction",
    "landCoverFragmentationIndex",
    "riparianForestFraction",
    "imperviousConnectivityProxy",
    "soilValidFraction",
    "meanClayTopsoilPct",
    "medianClayTopsoilPct",
    "p90ClayTopsoilPct",
    "meanSandTopsoilPct",
    "medianSandTopsoilPct",
    "lowPermeabilitySoilFraction",
    "highInfiltrationSoilFraction",
    "runoffPotentialIndex",
    "coarseFragmentFraction",
    "subsoilClayFraction",
    "subsoilSandFraction",
    "soilDepthMeanCm",
    "soilDepthShallowFractionLt100Cm",
    "bedrockDepthCm",
    "availableWaterCapacityMm",
    "saturatedHydraulicConductivityCmPerDay",
    "lakeFraction",
    "lakeCount",
    "reservoirCountUpstream",
    "regulatedLakeCountUpstream",
    "damCountUpstream",
    "majorReservoirDamCountUpstream",
    "reservoirAreaUpstreamKm2",
    "reservoirAreaFraction",
    "reservoirStorageUpstreamMcm",
    "largestUpstreamReservoirAreaKm2",
    "largestUpstreamReservoirStorageMcm",
    "regulatedCatchment",
    "gdwBarrierCountUpstream",
    "gdwReservoirCountUpstream",
    "gdwHydropowerBarrierCountUpstream",
    "gdwReservoirAreaUpstreamKm2",
    "gdwReservoirStorageUpstreamMcm",
    "gdwLargestReservoirStorageMcm",
    "gdwLargestReservoirAreaKm2",
    "gdwMaxUpstreamDorPct",
    "gdwNewestUpstreamDamYear",
    "gdwMaxDamHeightM",
    "gdwRegulatedCatchment",
    "geologyValidFraction",
    "carbonateFraction",
    "unconsolidatedFraction",
    "crystallineFraction",
    "volcanicFraction",
    "evaporiteFraction",
    "dominantLithologyCode",
    "karstIndicator",
    "imperviousValidFraction",
    "imperviousBuiltSurfaceFraction",
    "meanBuiltSurfaceM2PerCell",
    "imperviousProxyFraction",
    "glacierFraction",
    "glacierCount",
    "largestGlacierAreaKm2",
    "osmRegulationPresent",
    "osmDamCountUpstream",
    "osmWeirCountUpstream",
    "osmReservoirCountUpstream",
    "osmCanalCountUpstream",
    "osmPenstockCountUpstream",
    "osmHydropowerPlantCountUpstream",
    "osmOperatorEdfCountUpstream",
    "osmLikelyHydropowerScheme",
    "osmRegulationConfidence",
    "distanceToNearestRegulationUpstreamKm",
    "regulatedAreaFraction",
    "interbasinTransferLikely",
    "waterIntakeDensity",
    "hydropowerCascadeCount",
    "regulationSeverityIndex",
    "sinkholeDensityProxyPerKm2",
    "springDensityProxyPerKm2",
    "losingStreamProxy",
    "resurgenceProxy",
    "karstConnectivityProxy",
]
WATERSHED_DESCRIPTOR_REGULATION_TYPES = ["none", "hydropower", "barrier", "reservoir", "diversion", "mixed"]
WATERSHED_DESCRIPTOR_BOUNDED_UNIT_INTERVAL_KEYS = {
    "watershedNoDataFraction",
    "watershedQualityScore",
    "fractionAbove1500m",
    "fractionAbove2000m",
    "fractionAbove2500m",
    "fractionSlopeOver10Deg",
    "fractionSlopeOver20Deg",
    "fractionSlopeOver30Deg",
    "aspectNorthFraction",
    "aspectEastFraction",
    "aspectSouthFraction",
    "aspectWestFraction",
    "meanSnowFractionClimatology",
    "oceanicityProxy",
    "hypsometricIntegral",
    "valleyConfinementIndex",
    "firstOrderLengthFraction",
    "landCoverValidFraction",
    "forestFraction",
    "shrubFraction",
    "grassFraction",
    "croplandFraction",
    "urbanFraction",
    "bareRockFraction",
    "snowIceFraction",
    "permanentWaterFraction",
    "wetlandFraction",
    "mangroveFraction",
    "mossLichenFraction",
    "largestForestPatchFraction",
    "landCoverFragmentationIndex",
    "riparianForestFraction",
    "imperviousConnectivityProxy",
    "soilValidFraction",
    "lowPermeabilitySoilFraction",
    "highInfiltrationSoilFraction",
    "runoffPotentialIndex",
    "coarseFragmentFraction",
    "subsoilClayFraction",
    "subsoilSandFraction",
    "soilDepthShallowFractionLt100Cm",
    "lakeFraction",
    "reservoirAreaFraction",
    "regulatedAreaFraction",
    "regulationSeverityIndex",
    "geologyValidFraction",
    "carbonateFraction",
    "unconsolidatedFraction",
    "crystallineFraction",
    "volcanicFraction",
    "evaporiteFraction",
    "karstIndicator",
    "imperviousValidFraction",
    "imperviousBuiltSurfaceFraction",
    "imperviousProxyFraction",
    "glacierFraction",
    "osmRegulationConfidence",
    "losingStreamProxy",
    "resurgenceProxy",
    "karstConnectivityProxy",
}


def get_beautiful_soup() -> Any:
    try:
        from bs4 import BeautifulSoup  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "beautifulsoup4 is required for debit scraping. Install it with `python -m pip install beautifulsoup4`."
        ) from exc
    return BeautifulSoup

DEBIT_CLASS_MAP = {
    "debit1": "CRUE",
    "debit2": "TRES_GROS",
    "debit3": "GROS",
    "debit4": "CORRECT",
    "debit5": "FILET",
    "debit6": "SEC",
}

DEBIT_LEVEL_RANK = {
    "SEC": 0,
    "FILET": 1,
    "CORRECT": 2,
    "GROS": 3,
    "TRES_GROS": 4,
    "CRUE": 5,
    "INCONNU": -1,
}

GEO_POINT_PRIORITY = {
    "ENTREE": 0,
    "PARKING_AMONT": 1,
    "SORTIE": 2,
    "PARKING_AVAL": 3,
    "POINT_REMARQUABLE": 4,
    "ECHAPPATOIRE": 5,
    "UNKNOWN": 6,
}

WEATHER_SOURCE_BY_POINT_TYPE = {
    "ENTREE": "ENTRY",
    "PARKING_AMONT": "UPSTREAM_PARKING",
    "SORTIE": "EXIT",
    "PARKING_AVAL": "DOWNSTREAM_PARKING",
    "POINT_REMARQUABLE": "REMARKABLE_POINT",
    "ECHAPPATOIRE": "ESCAPE",
    "UNKNOWN": "UNKNOWN",
}

FRENCH_MONTHS = {
    "janvier": 1,
    "janv": 1,
    "fevrier": 2,
    "fevr": 2,
    "mars": 3,
    "avril": 4,
    "avr": 4,
    "mai": 5,
    "juin": 6,
    "juillet": 7,
    "juil": 7,
    "aout": 8,
    "septembre": 9,
    "sept": 9,
    "octobre": 10,
    "oct": 10,
    "novembre": 11,
    "nov": 11,
    "decembre": 12,
    "dec": 12,
}

FRENCH_LONG_REGEX = re.compile(r"(?:\w+\.?\s+)?(\d{1,2})\s+([a-z]+\.?)(?:\s+(\d{2,4}))")
DMY_SLASH_REGEX = re.compile(r"(?:\w+\.?\s+)?(\d{1,2})/(\d{1,2})/(\d{2,4})")
FRENCH_DAY_MONTH_REGEX = re.compile(r"(?:\w+\.?\s+)?(\d{1,2})/(\d{1,2})")
DMY_DASH_REGEX = re.compile(r"(\d{2})-(\d{2})-(\d{4})")

EXPLICIT_INVALID_PATTERNS = (
    "sans rapport avec le debit",
    "sans rapport avec le debit actuel",
    "nous serons en stage",
    "nous serons en formation",
    "journee de formation",
    "stage formation",
    "debit inconnu",
    "ni vu ni parcouru",
    "il risque donc d y avoir du monde",
)

STRONG_NON_HYDRO_INFO_KEYWORDS = {
    "cyclistes",
    "stationnement",
    "travaux",
    "chantier",
    "fermeture",
    "ferme",
    "fermee",
    "interdit",
    "interdite",
    "arrete",
    "propriete",
    "militaire",
    "rallye",
    "train",
}

WEAK_NON_HYDRO_INFO_KEYWORDS = {
    "circulation",
    "route",
    "vehicule",
    "vehicules",
    "voiture",
    "acces",
    "parking",
    "club",
    "groupe",
    "monde",
    "info",
    "formation",
    "stage",
    "ffcam",
    "ffme",
    "reservee",
    "reserve",
}

HYDROLOGY_SIGNAL_PATTERNS = {
    "dc shorthand": r"\bdc\b",
    "gd shorthand": r"\bgd\b",
    "top": r"\btop\b",
    "debit": r"\bdebit\b",
    "debit observe": r"\bdebit observe\b",
    "debit actuel": r"\bdebit actuel\b",
    "debit correct": r"\bdebit correct\b",
    "debit parfait": r"\bdebit parfait\b",
    "debit ok": r"\bdebit ok\b",
    "debit normal": r"\bdebit normal\b",
    "debit reel": r"\bdebit reel\b",
    "debit reserve": r"\bdebit reserve\b",
    "debit naturel": r"\bdebit naturel\b",
    "debit libre": r"\bdebit libre\b",
    "trop d eau": r"\btrop d eau\b",
    "gros debit": r"\bgros debit\b",
    "tres gros": r"\btres gros\b",
    "petit debit": r"\bpetit debit\b",
    "petit filet": r"\bpetit filet\b",
    "filet d eau": r"\bfilet d eau\b",
    "goutte a goutte": r"\bgoutte a goutte\b",
    "rien ne coule": r"\brien ne coule\b",
    "ne coule": r"\bne coule(?:nt)?\b",
    "ca coule": r"\bca coule\b",
    "en eau": r"\ben eau\b",
    "peu d eau": r"\b(?:peu|quasi pas|pas) d eau\b",
    "beaucoup d eau": r"\bbeaucoup d eau\b",
    "trace d eau": r"\btrace d eau\b",
    "sec": r"\bsec\b",
    "crue": r"\bcrue\b",
    "impraticable": r"\bimpraticable\b",
    "impassable": r"\bimpassable\b",
    "praticable": r"(?<!im)\bpraticable\b",
    "faisable": r"\bfaisable\b",
    "conditions": r"\ben conditions\b",
    "top conditions": r"\btop conditions?\b",
    "conditions ideales": r"\bconditions? ideal\w*\b",
    "vasque": r"\bvasque(?:s)?\b",
    "cascade": r"\bcascade(?:s)?\b",
    "saut": r"\bsaut(?:s)?\b",
    "toboggan": r"\btoboggan(?:s)?\b",
    "bouillonne": r"\bbouillonn\w*\b",
    "ca crache": r"\bca crache\b",
    "queue de cheval": r"\bqueue de cheval\b",
    "barrage": r"\bbarrage\b",
    "captage": r"\bcaptage\b",
    "capture": r"\bcapte\b",
    "lacher d eau": r"\blach(?:e|er|ers)? d eau\b",
    "deverse": r"\bdevers(?:e|er|ee|ement)\b",
    "turbinage": r"\bturbinage\b",
    "centrale": r"\bcentrale\b",
    "retenue": r"\bretenue\b",
    "robinet": r"\brobinet\b",
    "neige": r"\bneige\b",
    "neve": r"\bneve\b",
    "glace": r"\bglace\b",
    "avalanche": r"\bavalanche(?:s)?\b",
    "fonte": r"\bfonte\b",
    "caudal": r"\bcaudal\b",
    "portata": r"\bportata\b",
    "waterlevel": r"\bwater ?level\b",
    "too much water": r"\btoo much water\b",
    "troppa acqua": r"\btroppa acqua\b",
    "mucha agua": r"\bmucha agua\b",
    "too high": r"\btoo high\b",
    "vu du pont": r"\bvu du pont\b",
    "depuis le pont": r"\bdepuis le pont\b",
    "observation depuis": r"\bobservation(?:s)? depuis\b",
    "vu de la route": r"\bvu(?:e)? de la route\b",
    "vu depuis": r"\bvu(?:e)? depuis\b",
    "orage": r"\borage(?:s)?\b",
    "pluie": r"\bpluie(?:s)?\b",
    "juste ce qu il faut": r"\bjuste ce qu il faut\b",
    "a faire": r"\ba faire\b",
    "c est le moment": r"\bc est le moment\b",
    "au top": r"\bau top\b",
}

CURRENT_OBSERVATION_PATTERNS = {
    "aujourd hui": r"\baujourd hui\b",
    "ce jour": r"\bce jour\b",
    "actuellement": r"\bactuellement\b",
    "vu": r"\bvu(?:e)?\b",
    "observe": r"\bobserv\w+\b",
    "au parking": r"\bau parking\b",
    "au pont": r"\bau pont\b",
    "au depart": r"\bau depart\b",
    "dans le canyon": r"\bdans le canyon\b",
    "a l arrivee": r"\ba l arrivee\b",
    "ce matin": r"\bce matin\b",
    "ce soir": r"\bce soir\b",
    "debit est": r"\bdebit (?:est|etait|reste|demeure|redevient|redevenu)\b",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


DEBIT_DERIVED_MODEL_FEATURE_NAMES = (
    "precip7dWeeklyClimatologyRatio",
    "precip30dMonthlyClimatologyRatio",
    "api93MonthlyClimatologyRatio",
    "runoffPrecip7dSignal",
    "runoffPrecip30dSignal",
    "flashFlood7dSignal",
    "basinPrecipVolume30dProxy",
    "rainOnSnow7dSignal",
    "snowmeltDegreeDay14dSignal",
    "highElevationSnowmeltSignal",
    "canyonHistoryConfidence",
    "massifHistoryConfidence",
    "regionHistoryConfidence",
    "canyonHighPriorLift",
    "canyonLowPriorLift",
    "massifHighPriorLift",
    "regionHighPriorLift",
    "canyonPriorEntropy",
    "highLowPriorSpread",
)


def _model_feature_number(row: dict[str, Any], key: str) -> float | None:
    value = row.get(key)
    if value is None:
        return None
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    try:
        numeric_value = float(value)
    except (TypeError, ValueError):
        return None
    return numeric_value if math.isfinite(numeric_value) else None


def _model_ratio(numerator: float | None, denominator: float | None) -> float | None:
    if numerator is None or denominator is None or abs(denominator) < 1e-9:
        return None
    return round(numerator / denominator, 6)


def _model_product(*values: float | None) -> float | None:
    result = 1.0
    for value in values:
        if value is None:
            return None
        result *= value
    return round(result, 6)


def _history_confidence(count: float | None, strength: float) -> float:
    if count is None or count <= 0.0:
        return 0.0
    return round(count / (count + strength), 6)


def _prior_entropy(*probabilities: float | None) -> float | None:
    values = [value for value in probabilities if value is not None and value > 0.0]
    total = sum(values)
    if total <= 0.0:
        return None
    entropy = -sum((value / total) * math.log(value / total) for value in values)
    return round(entropy / math.log(len(probabilities)), 6) if len(probabilities) > 1 else 0.0


def compute_debit_derived_model_features(row: dict[str, Any]) -> dict[str, float | None]:
    mean_annual_precip = _model_feature_number(row, "meanAnnualPrecipMm")
    weekly_climatology = mean_annual_precip / 52.1775 if mean_annual_precip is not None else None
    monthly_climatology = mean_annual_precip / 12.0 if mean_annual_precip is not None else None

    precip_7d = _model_feature_number(row, "precip_7d_mm")
    precip_30d = _model_feature_number(row, "precip_30d_mm")
    api_93 = _model_feature_number(row, "antecedent_precipitation_index_daily_93")
    max_precip_7d = _model_feature_number(row, "max_daily_precip_7d_mm")
    rain_7d = _model_feature_number(row, "rain_7d_mm")
    snowfall_14d = _model_feature_number(row, "snowfall_14d_cm")
    positive_degree_days_14d = _model_feature_number(row, "positive_degree_days_14d")
    runoff_potential = _model_feature_number(row, "runoffPotentialIndex")
    flashiness = _model_feature_number(row, "watershedFlashinessProxy")
    basin_area = _model_feature_number(row, "basinAreaRasterKm2") or _model_feature_number(row, "upstreamCatchmentAreaKm2")
    fraction_above_1500m = _model_feature_number(row, "fractionAbove1500m")

    region_prior_high = _model_feature_number(row, "regionPriorHigh")
    massif_prior_high = _model_feature_number(row, "massifPriorHigh")
    canyon_prior_high = _model_feature_number(row, "canyonPriorHigh")
    canyon_prior_medium = _model_feature_number(row, "canyonPriorMedium")
    canyon_prior_low = _model_feature_number(row, "canyonPriorLow")
    massif_prior_low = _model_feature_number(row, "massifPriorLow")

    return {
        "precip7dWeeklyClimatologyRatio": _model_ratio(precip_7d, weekly_climatology),
        "precip30dMonthlyClimatologyRatio": _model_ratio(precip_30d, monthly_climatology),
        "api93MonthlyClimatologyRatio": _model_ratio(api_93, monthly_climatology),
        "runoffPrecip7dSignal": _model_product(precip_7d, runoff_potential),
        "runoffPrecip30dSignal": _model_product(precip_30d, runoff_potential),
        "flashFlood7dSignal": _model_product(max_precip_7d, flashiness),
        "basinPrecipVolume30dProxy": _model_product(precip_30d, basin_area),
        "rainOnSnow7dSignal": _model_product(rain_7d, snowfall_14d),
        "snowmeltDegreeDay14dSignal": _model_product(positive_degree_days_14d, snowfall_14d),
        "highElevationSnowmeltSignal": _model_product(positive_degree_days_14d, snowfall_14d, fraction_above_1500m),
        "canyonHistoryConfidence": _history_confidence(_model_feature_number(row, "canyonPastObsCount"), 10.0),
        "massifHistoryConfidence": _history_confidence(_model_feature_number(row, "massifPastObsCount"), 20.0),
        "regionHistoryConfidence": _history_confidence(_model_feature_number(row, "regionPastObsCount"), 30.0),
        "canyonHighPriorLift": round(canyon_prior_high - massif_prior_high, 6) if canyon_prior_high is not None and massif_prior_high is not None else None,
        "canyonLowPriorLift": round(canyon_prior_low - massif_prior_low, 6) if canyon_prior_low is not None and massif_prior_low is not None else None,
        "massifHighPriorLift": round(massif_prior_high - region_prior_high, 6) if massif_prior_high is not None and region_prior_high is not None else None,
        "regionHighPriorLift": round(region_prior_high - (_model_feature_number(row, "globalPriorHigh") or 0.0), 6) if region_prior_high is not None else None,
        "canyonPriorEntropy": _prior_entropy(canyon_prior_low, canyon_prior_medium, canyon_prior_high),
        "highLowPriorSpread": round(canyon_prior_high - canyon_prior_low, 6) if canyon_prior_high is not None and canyon_prior_low is not None else None,
    }


def with_debit_derived_model_features(row: dict[str, Any]) -> dict[str, Any]:
    enriched = dict(row)
    enriched.update(compute_debit_derived_model_features(enriched))
    return enriched


def stable_id(prefix: str, *values: Any) -> str:
    normalized = "|".join("" if value is None else str(value) for value in values)
    digest = hashlib.sha1(normalized.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}_{digest}"


def clean_text(value: str | None) -> str | None:
    if value is None:
        return None
    value = re.sub(r"\s+", " ", value).strip()
    return value or None


def normalize_text(value: str | None) -> str:
    if not value:
        return ""
    value = unicodedata.normalize("NFKD", value)
    value = "".join(character for character in value if not unicodedata.combining(character))
    value = value.lower()
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def unique_non_blank(values: list[str | None]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        cleaned = clean_text(value)
        if cleaned is None:
            continue
        key = cleaned.casefold()
        if key in seen:
            continue
        seen.add(key)
        result.append(cleaned)
    return result


def normalize_year(value: int) -> int:
    return 2000 + value if 0 <= value <= 99 else value


def parse_observation_date(raw: str) -> str | None:
    value = clean_text(raw)
    if not value:
        return None
    value = value.replace("\u00a0", " ")
    normalized = normalize_text(value)

    for parser in (
        _try_parse_iso,
        _try_parse_french_long,
        _try_parse_dmy_slash,
        _try_parse_french_day_month,
        _try_parse_dmy_dash,
    ):
        parsed = parser(normalized)
        if parsed is not None:
            return parsed.isoformat()
    return None


def _try_parse_iso(value: str) -> date | None:
    try:
        return datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError:
        return None


def _try_parse_french_long(value: str) -> date | None:
    match = FRENCH_LONG_REGEX.search(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = FRENCH_MONTHS.get(match.group(2).rstrip("."))
    year = normalize_year(int(match.group(3))) if match.group(3) else datetime.now().year
    if month is None:
        return None
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _try_parse_dmy_slash(value: str) -> date | None:
    match = DMY_SLASH_REGEX.fullmatch(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = int(match.group(2))
    year = normalize_year(int(match.group(3)))
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _try_parse_french_day_month(value: str) -> date | None:
    match = FRENCH_DAY_MONTH_REGEX.fullmatch(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = int(match.group(2))
    try:
        return date(datetime.now().year, month, day)
    except ValueError:
        return None


def _try_parse_dmy_dash(value: str) -> date | None:
    match = DMY_DASH_REGEX.fullmatch(value)
    if match is None:
        return None
    day = int(match.group(1))
    month = int(match.group(2))
    year = int(match.group(3))
    try:
        return date(year, month, day)
    except ValueError:
        return None


def fetch_text(
    url: str,
    *,
    user_agent: str = DEFAULT_USER_AGENT,
    timeout: int = 30,
    delay_seconds: float = 0.0,
    cache_path: Path | None = None,
) -> str:
    if cache_path is not None and cache_path.exists():
        return cache_path.read_text(encoding="utf-8")

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            if delay_seconds > 0:
                time.sleep(delay_seconds)
            request = Request(url, headers={"User-Agent": user_agent})
            with urlopen(request, timeout=timeout) as response:
                body = response.read().decode("utf-8", errors="replace")
            if cache_path is not None:
                cache_path.parent.mkdir(parents=True, exist_ok=True)
                cache_path.write_text(body, encoding="utf-8")
            return body
        except HTTPError:
            raise
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt == 2:
                raise
            time.sleep(1.5 * (attempt + 1))

    assert last_error is not None
    raise last_error


def parse_html_line_breaks(container: Any) -> list[str]:
    if container is None:
        return []
    html = container if isinstance(container, str) else str(container)
    BeautifulSoup = get_beautiful_soup()
    soup = BeautifulSoup(f"<div>{html}</div>", "html.parser")
    for br in soup.select("br"):
        br.replace_with("\n")
    text = soup.get_text("", strip=False)
    return [line for line in (clean_text(part) for part in text.splitlines()) if line]


# Titles of the counters the site shows under a member's name in a report remark.
SITE_MEMBER_STAT_KEYS = {
    "contribution aux fiches-canyons": "canyonSheetContributions",
    "géolocalisation": "geolocations",
    "débits signalés": "debitReports",
    "photos": "photos",
    "forums": "forumPosts",
}


def parse_report_author_block(user_block: Any) -> dict[str, Any]:
    """
    The author block of a report remark: a member shows the site counters (fiches, geolocations,
    debits, photos, forum posts), a name typed without an account shows "Utilisateur non
    enregistré". The avatar file is named after the member's forum id.
    """
    name_node = user_block.select_one("b")
    stats_node = user_block.select_one("div.poids")
    registered: bool | None = None
    stats: dict[str, int] = {}
    if stats_node is not None:
        if "non enregistre" in normalize_text(stats_node.get_text(" ", strip=True)):
            registered = False
        else:
            for counter in stats_node.select("span[title]"):
                key = SITE_MEMBER_STAT_KEYS.get(clean_text(counter.get("title")) or "")
                value = re.sub(r"\D", "", str(counter.previous_sibling or ""))
                if key and value:
                    stats[key] = int(value)
            registered = True if stats else None
    avatar = user_block.select_one("div.av img[src]")
    avatar_match = re.search(r"avatar=(\d+)", avatar.get("src", "")) if avatar is not None else None
    return {
        "author": clean_text(name_node.get_text(" ", strip=True) if name_node else None),
        "registered": registered,
        "forumUserId": int(avatar_match.group(1)) if avatar_match and registered else None,
        "siteStats": stats or None,
    }


def parse_observation_details(detail_row: Any) -> list[dict[str, Any]]:
    if detail_row is None:
        return []

    authors = [parse_report_author_block(user_block) for user_block in detail_row.select("div.userc")]
    comments = [clean_text(paragraph.get_text(" ", strip=True)) for paragraph in detail_row.select("p")]
    count = max(len(authors), len(comments))
    details: list[dict[str, Any]] = []
    for index in range(count):
        profile = authors[index] if index < len(authors) else {"author": None, "registered": None, "forumUserId": None, "siteStats": None}
        comment = comments[index] if index < len(comments) else None
        if profile["author"] is None and comment is None:
            continue
        details.append({**profile, "comment": comment})
    return details


def parse_canyon_debit_page(
    *,
    canyon_id: int,
    canyon_name: str | None,
    html: str,
    source_url: str,
    assumed_observation_hour: int = DEFAULT_ASSUMED_OBSERVATION_HOUR,
) -> list[dict[str, Any]]:
    BeautifulSoup = get_beautiful_soup()
    document = BeautifulSoup(html, "html.parser")
    rows = document.select("table#listedebit tbody tr")
    # Remarks are hidden rows "tr#tr<id>": indexed once, a lookup per report is quadratic.
    remark_rows: dict[str, Any] = {}
    for remark_row in document.select("tr[id^=tr]"):
        remark_rows.setdefault(remark_row.get("id"), remark_row)
    events: list[dict[str, Any]] = []

    for row_index, row in enumerate(rows, start=1):
        if row.select("td[colspan]"):
            continue

        row_class = " ".join(row.get("class", []))
        level = next((mapped for css_class, mapped in DEBIT_CLASS_MAP.items() if css_class in row_class), None)
        if level is None:
            continue

        cells = row.select("td")
        if len(cells) < 4:
            continue

        date_raw = clean_text(cells[0].get_text(" ", strip=True))
        date_iso = parse_observation_date(date_raw or "")

        authors = parse_html_line_breaks(cells[1].decode_contents())
        observation_icon = cells[2].select_one("span")
        observation_title = clean_text(observation_icon.get("title") if observation_icon is not None else None)
        observation_title_normalized = normalize_text(observation_title)
        if "parcouru" in observation_title_normalized and "non" in observation_title_normalized:
            is_descended = False
        elif "parcouru" in observation_title_normalized:
            is_descended = True
        else:
            is_descended = None

        remark_button = row.select_one("button.lire")
        remark_id = None
        if remark_button is not None:
            raw_id = clean_text(remark_button.get("id"))
            if raw_id and raw_id.startswith("r"):
                remark_id = raw_id[1:]

        detail_row = remark_rows.get(f"tr{remark_id}") if remark_id else None
        observation_details = parse_observation_details(detail_row)
        detail_comments = unique_non_blank([detail.get("comment") for detail in observation_details])
        primary_comment = "\n\n---\n\n".join(detail_comments) if detail_comments else None

        timestamp_local = None
        if date_iso is not None:
            timestamp_local = datetime.combine(date.fromisoformat(date_iso), datetime_time(hour=assumed_observation_hour)).isoformat(timespec="seconds")

        event_id = stable_id(
            "obs",
            canyon_id,
            date_iso or date_raw,
            level,
            remark_id,
            "|".join(authors),
            primary_comment,
        )
        events.append(
            {
                "observationId": event_id,
                "canyonId": canyon_id,
                "canyonName": canyon_name,
                "dateRaw": date_raw,
                "date": date_iso,
                "assumedObservationHourLocal": assumed_observation_hour,
                "assumedObservationTimeLocal": timestamp_local,
                "niveau": level,
                "niveauRank": DEBIT_LEVEL_RANK.get(level, -1),
                "authors": authors,
                "authorCount": len(authors),
                "primaryAuthor": authors[0] if authors else None,
                "isDescended": is_descended,
                "waterTemperature": clean_text(cells[4].get_text(" ", strip=True)) if len(cells) > 4 else None,
                "airTemperature": clean_text(cells[5].get_text(" ", strip=True)) if len(cells) > 5 else None,
                "comment": primary_comment,
                "comments": detail_comments,
                # Only the authors who wrote a remark: whether they have an account, and their counters.
                "authorProfiles": [
                    {key: detail[key] for key in ("author", "registered", "forumUserId", "siteStats")}
                    for detail in observation_details
                    if detail.get("author")
                ],
                "remarkId": remark_id,
                "observationTitle": observation_title,
                "sourceUrl": source_url,
                "rowIndex": row_index,
                "rowClass": row_class,
            }
        )

    return events


def load_manual_overrides(path: Path | None) -> list[dict[str, Any]]:
    if path is None or not path.exists():
        return []
    payload = load_json(path)
    if not isinstance(payload, list):
        raise SystemExit(f"Manual overrides must be a JSON array: {path}")
    return payload


def match_manual_override(observation: dict[str, Any], overrides: list[dict[str, Any]]) -> dict[str, Any] | None:
    authors_normalized = {normalize_text(author) for author in observation.get("authors", []) if author}
    for override in overrides:
        override_observation_id = override.get("observationId")
        if override_observation_id is not None:
            if override_observation_id == observation.get("observationId"):
                return override
            continue
        if override.get("canyonId") not in (None, observation.get("canyonId")):
            continue
        if override.get("date") not in (None, observation.get("date")):
            continue
        override_author = normalize_text(override.get("author"))
        if override_author and override_author not in authors_normalized:
            continue
        if override.get("remarkId") not in (None, observation.get("remarkId")):
            continue
        return override
    return None


def classify_observation(observation: dict[str, Any], overrides: list[dict[str, Any]]) -> dict[str, Any]:
    manual_override = match_manual_override(observation, overrides)
    if manual_override is not None:
        action = manual_override.get("action", "invalid")
        return {
            "qualityLabel": action,
            "qualityScore": _quality_score_for_label(action),
            "qualityReasons": [f"manual_override:{manual_override.get('reason', 'unspecified')}"] ,
            "manualOverride": True,
        }

    comment = observation.get("comment")
    normalized_comment = normalize_text(comment)
    is_descended = observation.get("isDescended")
    reasons: list[str] = []

    if observation.get("date") is None:
        reasons.append("missing_parsed_date")
        return {
            "qualityLabel": "uncertain",
            "qualityScore": _quality_score_for_label("uncertain"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    explicit_invalid_hits = [pattern for pattern in EXPLICIT_INVALID_PATTERNS if pattern in normalized_comment]
    strong_non_hydro_hits = sorted(
        keyword
        for keyword in STRONG_NON_HYDRO_INFO_KEYWORDS
        if re.search(rf"\b{re.escape(keyword)}\b", normalized_comment)
    )
    weak_non_hydro_hits = sorted(
        keyword
        for keyword in WEAK_NON_HYDRO_INFO_KEYWORDS
        if re.search(rf"\b{re.escape(keyword)}\b", normalized_comment)
    )
    hydro_signal_hits = [
        label
        for label, pattern in HYDROLOGY_SIGNAL_PATTERNS.items()
        if re.search(pattern, normalized_comment)
    ]
    current_observation_hits = [
        label
        for label, pattern in CURRENT_OBSERVATION_PATTERNS.items()
        if re.search(pattern, normalized_comment)
    ]
    has_hydrology_comment_signal = bool(hydro_signal_hits)
    has_current_observation_context = bool(current_observation_hits)
    has_strong_non_hydro_signal = bool(strong_non_hydro_hits)
    has_weak_non_hydro_signal = bool(weak_non_hydro_hits)

    if explicit_invalid_hits and is_descended is not True:
        reasons.extend(f"explicit_invalid:{pattern}" for pattern in explicit_invalid_hits)
        return {
            "qualityLabel": "invalid",
            "qualityScore": _quality_score_for_label("invalid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if is_descended is True:
        reasons.append("descended")
        if hydro_signal_hits:
            reasons.extend(f"hydrology_signal:{phrase}" for phrase in hydro_signal_hits)
        if strong_non_hydro_hits or weak_non_hydro_hits:
            reasons.append("descended_with_non_hydro_context")
        return {
            "qualityLabel": "valid",
            "qualityScore": _quality_score_for_label("valid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if is_descended is False:
        if not normalized_comment:
            reasons.append("non_descended_without_comment_but_level_selected")
            return {
                "qualityLabel": "valid",
                "qualityScore": _quality_score_for_label("valid"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_hydrology_comment_signal:
            reasons.extend(f"hydrology_signal:{phrase}" for phrase in hydro_signal_hits)
            if has_current_observation_context or not has_strong_non_hydro_signal:
                reasons.append("non_descended_hydrology_observation")
                return {
                    "qualityLabel": "valid",
                    "qualityScore": _quality_score_for_label("valid"),
                    "qualityReasons": reasons,
                    "manualOverride": False,
                }
            reasons.extend(f"context_only:{keyword}" for keyword in strong_non_hydro_hits)
            reasons.append("mixed_hydrology_and_logistics")
            return {
                "qualityLabel": "uncertain",
                "qualityScore": _quality_score_for_label("uncertain"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_strong_non_hydro_signal:
            reasons.extend(f"non_hydro_info:{keyword}" for keyword in strong_non_hydro_hits)
            return {
                "qualityLabel": "invalid",
                "qualityScore": _quality_score_for_label("invalid"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_weak_non_hydro_signal:
            reasons.extend(f"context_only:{keyword}" for keyword in weak_non_hydro_hits)
            reasons.append("non_descended_context_without_hydrology_signal")
            return {
                "qualityLabel": "uncertain",
                "qualityScore": _quality_score_for_label("uncertain"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        reasons.append("non_descended_ambiguous_comment")
        return {
            "qualityLabel": "uncertain",
            "qualityScore": _quality_score_for_label("uncertain"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if has_hydrology_comment_signal:
        reasons.extend(f"hydrology_signal:{phrase}" for phrase in hydro_signal_hits)
        if has_current_observation_context:
            reasons.append("hydrology_observation_without_status")
            return {
                "qualityLabel": "valid",
                "qualityScore": _quality_score_for_label("valid"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        if has_strong_non_hydro_signal or has_weak_non_hydro_signal:
            reasons.append("mixed_hydrology_and_logistics")
            return {
                "qualityLabel": "uncertain",
                "qualityScore": _quality_score_for_label("uncertain"),
                "qualityReasons": reasons,
                "manualOverride": False,
            }
        reasons.append("default_hydrology_valid")
        return {
            "qualityLabel": "valid",
            "qualityScore": _quality_score_for_label("valid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    if has_strong_non_hydro_signal and not has_hydrology_comment_signal:
        reasons.extend(f"non_hydro_info:{keyword}" for keyword in strong_non_hydro_hits)
        return {
            "qualityLabel": "invalid",
            "qualityScore": _quality_score_for_label("invalid"),
            "qualityReasons": reasons,
            "manualOverride": False,
        }

    reasons.append("default_valid")
    return {
        "qualityLabel": "valid",
        "qualityScore": _quality_score_for_label("valid"),
        "qualityReasons": reasons,
        "manualOverride": False,
    }


def _quality_score_for_label(label: str) -> float:
    if label == "valid":
        return 1.0
    if label == "uncertain":
        return 0.5
    return 0.0


def deduplicate_observations(observations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[Any, ...]] = set()
    deduplicated: list[dict[str, Any]] = []
    for observation in observations:
        key = (
            observation.get("canyonId"),
            observation.get("date"),
            observation.get("niveau"),
            tuple(normalize_text(author) for author in observation.get("authors", [])),
            normalize_text(observation.get("comment")),
            observation.get("remarkId"),
        )
        if key in seen:
            continue
        seen.add(key)
        deduplicated.append(observation)
    return deduplicated


def load_canyon_lookup(canyons_path: Path) -> dict[int, dict[str, Any]]:
    rows = load_json(canyons_path)
    return {int(row["id"]): row for row in rows}


def load_geo_points_lookup(geo_points_path: Path) -> dict[int, list[dict[str, Any]]]:
    grouped: dict[int, list[dict[str, Any]]] = {}
    for row in load_json(geo_points_path):
        grouped.setdefault(int(row["canyonId"]), []).append(row)
    return grouped


def load_watershed_lookup(watersheds_path: Path) -> dict[int, dict[str, Any]]:
    rows = load_json(watersheds_path)
    return {int(row["canyonId"]): row for row in rows}


def normalize_watershed_descriptor_row(row: dict[str, Any]) -> dict[str, Any]:
    normalized: dict[str, Any] = {}
    for key in WATERSHED_DESCRIPTOR_NUMERIC_KEYS:
        normalized[key] = _sanitize_watershed_descriptor_value(key, row.get(key))

    normalized["watershedDescriptorForcedByReview"] = bool(row.get("forcedByReview"))

    for status_key, flag_key in WATERSHED_DESCRIPTOR_STATUS_FLAG_MAP.items():
        normalized[flag_key] = row.get(status_key) == "ok"

    normalized["watershedDescriptorOk"] = normalized["descriptorStatusOk"]
    normalized["climateDescriptorOk"] = normalized["climateDescriptorStatusOk"]
    normalized["soilDescriptorOk"] = normalized["soilDescriptorStatusOk"]
    normalized["hydroLakesDescriptorOk"] = normalized["hydroLakesStatusOk"]
    normalized["gdwDescriptorOk"] = normalized["gdwStatusOk"]
    normalized["geologyDescriptorOk"] = normalized["geologyDescriptorStatusOk"]
    normalized["imperviousDescriptorOk"] = normalized["imperviousDescriptorStatusOk"]
    normalized["glacierDescriptorOk"] = normalized["glacierDescriptorStatusOk"]
    normalized["osmRegulationDescriptorOk"] = normalized["osmRegulationStatusOk"]

    regulation_type = row.get("dominantRegulationType")
    for value in WATERSHED_DESCRIPTOR_REGULATION_TYPES:
        normalized[f"dominantRegulationTypeIs{value.title()}"] = regulation_type == value

    normalized["hasClimateDescriptors"] = row.get("climateDescriptorStatus") == "ok"
    normalized["hasSoilDescriptors"] = row.get("soilDescriptorStatus") == "ok"
    normalized["hasRegulationDescriptors"] = (
        row.get("hydroLakesStatus") == "ok"
        or row.get("gdwStatus") == "ok"
        or row.get("osmRegulationStatus") == "ok"
    )
    normalized["hasGeologyDescriptors"] = row.get("geologyDescriptorStatus") == "ok"
    normalized["hasImperviousDescriptors"] = row.get("imperviousDescriptorStatus") == "ok"
    normalized["hasGlacierDescriptors"] = row.get("glacierDescriptorStatus") == "ok"
    normalized["hasWatershedDescriptors"] = row.get("descriptorStatus") == "ok"

    return normalized


def _sanitize_watershed_descriptor_value(key: str, value: Any) -> Any:
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        numeric_value = float(value)
        if not math.isfinite(numeric_value):
            return None
        if key in WATERSHED_DESCRIPTOR_BOUNDED_UNIT_INTERVAL_KEYS:
            if -0.01 <= numeric_value < 0.0:
                return 0.0
            if 1.0 < numeric_value <= 1.01:
                return 1.0
            if numeric_value < 0.0 or numeric_value > 1.0:
                return None
        return numeric_value
    return value


def resolve_watershed_descriptors_path(path: Path) -> Path:
    if path.is_file():
        return path
    direct = path / "import_ready_watershed_descriptors.json"
    if direct.exists():
        return direct
    matches = sorted(path.glob("**/import_ready_watershed_descriptors.json"))
    if matches:
        return matches[-1]
    return direct


def load_watershed_descriptors_lookup(path: Path | None) -> dict[int, dict[str, Any]]:
    if path is None:
        return {}
    resolved_path = resolve_watershed_descriptors_path(path)
    if not resolved_path.exists():
        return {}
    payload = load_json(resolved_path)
    if not isinstance(payload, list):
        raise SystemExit(f"Watershed descriptors must be a JSON array: {resolved_path}")
    return {
        int(row["canyonId"]): normalize_watershed_descriptor_row(row)
        for row in payload
        if row.get("canyonId") is not None
    }


def compute_watershed_morphology_features(watershed: dict[str, Any] | None) -> dict[str, Any]:
    features = {
        "watershedHasGeometry": False,
        "watershedPerimeterKm": None,
        "watershedCompactnessCoefficient": None,
        "watershedCircularityRatio": None,
        "watershedBboxWidthKm": None,
        "watershedBboxHeightKm": None,
        "watershedBboxDiagonalKm": None,
        "watershedBboxAreaKm2": None,
        "watershedAreaToBboxRatio": None,
        "watershedLengthProxyKm": None,
        "watershedWidthProxyKm": None,
        "watershedElongationRatio": None,
        "watershedFormFactor": None,
        "watershedShapeFactor": None,
        "watershedGeometryVertexCount": None,
    }
    if watershed is None:
        return features

    area_km2 = watershed.get("upstreamCatchmentAreaKm2")
    bbox = watershed.get("bbox")
    geometry = watershed.get("geometry")

    if bbox is not None and isinstance(bbox, list) and len(bbox) == 4:
        min_lon, min_lat, max_lon, max_lat = [float(value) for value in bbox]
        center_lat_rad = math.radians((min_lat + max_lat) / 2.0)
        width_km = abs(math.radians(max_lon - min_lon) * EARTH_RADIUS_KM * math.cos(center_lat_rad))
        height_km = abs(math.radians(max_lat - min_lat) * EARTH_RADIUS_KM)
        diagonal_km = math.hypot(width_km, height_km)
        bbox_area_km2 = width_km * height_km
        features.update(
            {
                "watershedBboxWidthKm": round(width_km, 6),
                "watershedBboxHeightKm": round(height_km, 6),
                "watershedBboxDiagonalKm": round(diagonal_km, 6),
                "watershedBboxAreaKm2": round(bbox_area_km2, 6),
            }
        )
        if area_km2 is not None and bbox_area_km2 > 0:
            features["watershedAreaToBboxRatio"] = round(float(area_km2) / bbox_area_km2, 6)

    if geometry is None or not isinstance(geometry, dict):
        return _finalize_watershed_shape_features(features, area_km2)

    polygons = _extract_watershed_polygons(geometry)
    if not polygons:
        return _finalize_watershed_shape_features(features, area_km2)

    reference_lat = _reference_latitude_for_polygons(polygons)
    perimeter_km = 0.0
    vertex_count = 0
    for polygon in polygons:
        if not polygon:
            continue
        exterior_ring = polygon[0]
        if len(exterior_ring) < 2:
            continue
        perimeter_km += _ring_perimeter_km(exterior_ring, reference_lat)
        vertex_count += max(len(exterior_ring) - 1, 0)

    if perimeter_km > 0:
        features["watershedHasGeometry"] = True
        features["watershedPerimeterKm"] = round(perimeter_km, 6)
        features["watershedGeometryVertexCount"] = vertex_count

    return _finalize_watershed_shape_features(features, area_km2)


def compute_watershed_response_proxy_features(
    canyon: dict[str, Any] | None,
    watershed: dict[str, Any] | None,
    morphology_features: dict[str, Any] | None = None,
) -> dict[str, Any]:
    features = {
        "watershedReliefProxyM": None,
        "watershedReliefPerLengthProxyMPerKm": None,
        "watershedReliefPerDiagonalProxyMPerKm": None,
        "watershedSlopeProxyPercent": None,
        "watershedSlopeDiagonalProxyPercent": None,
        "watershedReliefAreaRatioMPerKm2": None,
        "watershedKirpichTimeProxyMinutes": None,
        "watershedFlashinessProxy": None,
        "watershedShapeReliefInteraction": None,
    }
    if canyon is None:
        return features

    denivele_value = canyon.get("denivele")
    area_value = watershed.get("upstreamCatchmentAreaKm2") if watershed is not None else None
    if denivele_value is None:
        return features

    relief_m = float(denivele_value)
    features["watershedReliefProxyM"] = relief_m

    morphology = morphology_features or {}
    length_proxy_km = morphology.get("watershedLengthProxyKm")
    diagonal_proxy_km = morphology.get("watershedBboxDiagonalKm")
    elongation_ratio = morphology.get("watershedElongationRatio")
    circularity_ratio = morphology.get("watershedCircularityRatio")

    if area_value is not None and float(area_value) > 0:
        features["watershedReliefAreaRatioMPerKm2"] = round(relief_m / float(area_value), 6)

    if length_proxy_km is not None and float(length_proxy_km) > 0:
        length_proxy_value = float(length_proxy_km)
        relief_per_length = relief_m / length_proxy_value
        slope_fraction = relief_m / (length_proxy_value * 1000.0)
        features["watershedReliefPerLengthProxyMPerKm"] = round(relief_per_length, 6)
        features["watershedSlopeProxyPercent"] = round(slope_fraction * 100.0, 6)
        if slope_fraction > 0:
            # Kirpich formula using a geometry-derived channel-length proxy.
            features["watershedKirpichTimeProxyMinutes"] = round(
                0.01947 * ((length_proxy_value * 1000.0) ** 0.77) * (slope_fraction ** -0.385),
                6,
            )
        if circularity_ratio is not None and area_value is not None and float(area_value) > 0:
            features["watershedFlashinessProxy"] = round(
                float(circularity_ratio) * slope_fraction / math.sqrt(float(area_value)),
                8,
            )
        if elongation_ratio is not None:
            features["watershedShapeReliefInteraction"] = round(
                float(elongation_ratio) * slope_fraction,
                8,
            )

    if diagonal_proxy_km is not None and float(diagonal_proxy_km) > 0:
        diagonal_value = float(diagonal_proxy_km)
        relief_per_diagonal = relief_m / diagonal_value
        diagonal_slope_fraction = relief_m / (diagonal_value * 1000.0)
        features["watershedReliefPerDiagonalProxyMPerKm"] = round(relief_per_diagonal, 6)
        features["watershedSlopeDiagonalProxyPercent"] = round(diagonal_slope_fraction * 100.0, 6)

    return features


def _finalize_watershed_shape_features(features: dict[str, Any], area_km2: Any) -> dict[str, Any]:
    area_value = float(area_km2) if area_km2 is not None else None
    perimeter_value = features.get("watershedPerimeterKm")
    width_value = features.get("watershedBboxWidthKm")
    height_value = features.get("watershedBboxHeightKm")

    if area_value is not None and width_value is not None and height_value is not None:
        length_proxy = max(width_value, height_value)
        if length_proxy > 0:
            width_proxy = area_value / length_proxy
            features["watershedLengthProxyKm"] = round(length_proxy, 6)
            features["watershedWidthProxyKm"] = round(width_proxy, 6)
            if width_proxy > 0:
                features["watershedElongationRatio"] = round(length_proxy / width_proxy, 6)
            features["watershedFormFactor"] = round(area_value / (length_proxy * length_proxy), 6)
            if area_value > 0:
                features["watershedShapeFactor"] = round((length_proxy * length_proxy) / area_value, 6)

    if area_value is not None and area_value > 0 and perimeter_value is not None and perimeter_value > 0:
        features["watershedCompactnessCoefficient"] = round(
            perimeter_value / (2.0 * math.sqrt(math.pi * area_value)),
            6,
        )
        features["watershedCircularityRatio"] = round(
            (4.0 * math.pi * area_value) / (perimeter_value * perimeter_value),
            6,
        )

    return features


def _extract_watershed_polygons(geometry: dict[str, Any]) -> list[list[list[list[float]]]]:
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if geometry_type == "Polygon" and isinstance(coordinates, list):
        return [coordinates]
    if geometry_type == "MultiPolygon" and isinstance(coordinates, list):
        return coordinates
    return []


def _reference_latitude_for_polygons(polygons: list[list[list[list[float]]]]) -> float:
    latitudes: list[float] = []
    for polygon in polygons:
        for ring in polygon:
            for point in ring:
                if isinstance(point, list) and len(point) >= 2:
                    latitudes.append(float(point[1]))
    return sum(latitudes) / len(latitudes) if latitudes else 0.0


def _ring_perimeter_km(ring: list[list[float]], reference_lat: float) -> float:
    if len(ring) < 2:
        return 0.0
    total = 0.0
    for start, end in zip(ring, ring[1:]):
        total += _segment_length_km(start, end, reference_lat)
    return total


def _segment_length_km(start: list[float], end: list[float], reference_lat: float) -> float:
    start_lon, start_lat = float(start[0]), float(start[1])
    end_lon, end_lat = float(end[0]), float(end[1])
    x_scale = EARTH_RADIUS_KM * math.cos(math.radians(reference_lat))
    dx = math.radians(end_lon - start_lon) * x_scale
    dy = math.radians(end_lat - start_lat) * EARTH_RADIUS_KM
    return math.hypot(dx, dy)


def build_weather_target(
    *,
    canyon: dict[str, Any],
    geo_points: list[dict[str, Any]],
    watershed: dict[str, Any] | None,
) -> dict[str, Any] | None:
    canyon_id = int(canyon["id"])
    if watershed is not None:
        bbox = watershed.get("bbox") or []
        if len(bbox) == 4:
            min_longitude, min_latitude, max_longitude, max_latitude = bbox
            latitude = (float(min_latitude) + float(max_latitude)) / 2.0
            longitude = (float(min_longitude) + float(max_longitude)) / 2.0
            target_id = stable_id("target", "watershed", round(latitude, 6), round(longitude, 6))
            return {
                "targetId": target_id,
                "canyonId": canyon_id,
                "canyonName": canyon.get("nom"),
                "latitude": latitude,
                "longitude": longitude,
                "source": "WATERSHED_BBOX_CENTER",
                "upstreamCatchmentAreaKm2": watershed.get("upstreamCatchmentAreaKm2"),
                "bbox": bbox,
            }

    best_point = min(geo_points, key=lambda point: GEO_POINT_PRIORITY.get(point.get("type"), 99), default=None)
    if best_point is None:
        return None
    target_id = stable_id(
        "target",
        "point",
        best_point.get("type"),
        round(float(best_point["latitude"]), 6),
        round(float(best_point["longitude"]), 6),
    )
    return {
        "targetId": target_id,
        "canyonId": canyon_id,
        "canyonName": canyon.get("nom"),
        "latitude": float(best_point["latitude"]),
        "longitude": float(best_point["longitude"]),
        "source": WEATHER_SOURCE_BY_POINT_TYPE.get(best_point.get("type"), "UNKNOWN"),
        "upstreamCatchmentAreaKm2": None,
        "geoPointType": best_point.get("type"),
        "geoPointLabel": best_point.get("label"),
    }


def build_observation_window(
    observation: dict[str, Any],
    target: dict[str, Any],
    *,
    lookback_days: int,
) -> dict[str, Any]:
    observation_time = datetime.fromisoformat(observation["assumedObservationTimeLocal"])
    start_time = observation_time - timedelta(days=lookback_days)
    end_time = observation_time
    window_id = stable_id("obswindow", observation["observationId"], target["targetId"], start_time.isoformat(), end_time.isoformat())
    return {
        "observationWindowId": window_id,
        "observationId": observation["observationId"],
        "canyonId": observation["canyonId"],
        "targetId": target["targetId"],
        "targetLatitude": target["latitude"],
        "targetLongitude": target["longitude"],
        "targetSource": target["source"],
        "windowStartLocal": start_time.isoformat(timespec="seconds"),
        "windowEndLocal": end_time.isoformat(timespec="seconds"),
        "archiveStartDate": start_time.date().isoformat(),
        "archiveEndDate": end_time.date().isoformat(),
    }


def merge_windows(
    observation_windows: list[dict[str, Any]],
    *,
    max_gap_days: int = 0,
    max_span_days: int | None = None,
) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    grouped: dict[str, list[dict[str, Any]]] = {}
    for window in observation_windows:
        grouped.setdefault(window["targetId"], []).append(window)

    max_gap = timedelta(days=max(max_gap_days, 0))
    max_span = timedelta(days=max_span_days) if max_span_days is not None else None

    for target_id, windows in grouped.items():
        ordered = sorted(windows, key=lambda item: item["windowStartLocal"])
        current: dict[str, Any] | None = None
        current_observation_ids: list[str] = []
        current_canyon_ids: set[int] = set()

        for window in ordered:
            start = datetime.fromisoformat(window["windowStartLocal"])
            end = datetime.fromisoformat(window["windowEndLocal"])
            if current is None:
                current = dict(window)
                current["mergedWindowId"] = stable_id("mergedwindow", target_id, window["windowStartLocal"], window["windowEndLocal"])
                current_observation_ids = [window["observationId"]]
                current_canyon_ids = {int(window["canyonId"])}
                continue

            current_end = datetime.fromisoformat(current["windowEndLocal"])
            current_start = datetime.fromisoformat(current["windowStartLocal"])
            merged_end = max(current_end, end)
            gap = start - current_end
            span = merged_end - current_start
            can_merge = start <= current_end or gap <= max_gap
            if can_merge and (max_span is None or span <= max_span):
                if end > current_end:
                    current["windowEndLocal"] = window["windowEndLocal"]
                    current["archiveEndDate"] = window["archiveEndDate"]
                current_observation_ids.append(window["observationId"])
                current_canyon_ids.add(int(window["canyonId"]))
                continue

            current["observationIds"] = sorted(current_observation_ids)
            current["observationCount"] = len(current_observation_ids)
            current["canyonIds"] = sorted(current_canyon_ids)
            current["fetchSpanDays"] = (
                datetime.fromisoformat(current["windowEndLocal"]) - datetime.fromisoformat(current["windowStartLocal"])
            ).days
            merged.append(current)

            current = dict(window)
            current["mergedWindowId"] = stable_id("mergedwindow", target_id, window["windowStartLocal"], window["windowEndLocal"])
            current_observation_ids = [window["observationId"]]
            current_canyon_ids = {int(window["canyonId"])}

        if current is not None:
            current["observationIds"] = sorted(current_observation_ids)
            current["observationCount"] = len(current_observation_ids)
            current["canyonIds"] = sorted(current_canyon_ids)
            current["fetchSpanDays"] = (
                datetime.fromisoformat(current["windowEndLocal"]) - datetime.fromisoformat(current["windowStartLocal"])
            ).days
            merged.append(current)

    return sorted(merged, key=lambda item: (item["targetId"], item["windowStartLocal"]))


def build_annual_windows(observation_windows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, int], list[dict[str, Any]]] = {}
    for window in observation_windows:
        end_year = datetime.fromisoformat(window["windowEndLocal"]).year
        grouped.setdefault((window["targetId"], end_year), []).append(window)

    annual_windows: list[dict[str, Any]] = []
    for (target_id, year), windows in sorted(grouped.items(), key=lambda item: (item[0][0], item[0][1])):
        ordered = sorted(windows, key=lambda item: item["windowStartLocal"])
        first = dict(ordered[0])
        start_local = min(window["windowStartLocal"] for window in ordered)
        end_local = max(window["windowEndLocal"] for window in ordered)
        annual_window = dict(first)
        annual_window["mergedWindowId"] = stable_id("annualwindow", target_id, year, start_local, end_local)
        annual_window["windowStartLocal"] = start_local
        annual_window["windowEndLocal"] = end_local
        annual_window["archiveStartDate"] = min(window["archiveStartDate"] for window in ordered)
        annual_window["archiveEndDate"] = max(window["archiveEndDate"] for window in ordered)
        annual_window["observationIds"] = sorted(window["observationId"] for window in ordered)
        annual_window["observationCount"] = len(ordered)
        annual_window["canyonIds"] = sorted({int(window["canyonId"]) for window in ordered})
        annual_window["fetchSpanDays"] = (
            datetime.fromisoformat(end_local) - datetime.fromisoformat(start_local)
        ).days
        annual_window["fetchStrategy"] = "annual"
        annual_windows.append(annual_window)

    return annual_windows


def build_target_history_windows(
    observation_windows: list[dict[str, Any]],
    *,
    history_end_date: str,
) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for window in observation_windows:
        grouped.setdefault(window["targetId"], []).append(window)

    history_windows: list[dict[str, Any]] = []
    for target_id, windows in sorted(grouped.items(), key=lambda item: item[0]):
        ordered = sorted(windows, key=lambda item: item["windowStartLocal"])
        first = dict(ordered[0])
        start_local = min(window["windowStartLocal"] for window in ordered)
        end_local = max(window["windowEndLocal"] for window in ordered)
        history_window = dict(first)
        history_window["mergedWindowId"] = stable_id(
            "targethistory",
            target_id,
            start_local,
            history_end_date,
        )
        history_window["windowStartLocal"] = start_local
        history_window["windowEndLocal"] = end_local
        history_window["archiveStartDate"] = min(window["archiveStartDate"] for window in ordered)
        history_window["archiveEndDate"] = history_end_date
        history_window["observationIds"] = sorted(window["observationId"] for window in ordered)
        history_window["observationCount"] = len(ordered)
        history_window["canyonIds"] = sorted({int(window["canyonId"]) for window in ordered})
        history_window["fetchSpanDays"] = (
            date.fromisoformat(history_end_date) - date.fromisoformat(history_window["archiveStartDate"])
        ).days
        history_window["fetchStrategy"] = "target_history_daily"
        history_windows.append(history_window)

    return history_windows


def build_open_meteo_archive_url(
    *,
    latitude: float,
    longitude: float,
    start_date: str,
    end_date: str,
    model: str,
    hourly_variables: list[str],
    timezone: str = "auto",
) -> str:
    params = {
        "latitude": latitude,
        "longitude": longitude,
        "start_date": start_date,
        "end_date": end_date,
        "hourly": ",".join(hourly_variables),
        "timezone": timezone,
        "models": model,
    }
    return f"{OPEN_METEO_ARCHIVE_URL}?{urlencode(params)}"


def build_open_meteo_archive_daily_url(
    *,
    latitude: float,
    longitude: float,
    start_date: str,
    end_date: str,
    model: str,
    daily_variables: list[str],
    timezone: str = "auto",
) -> str:
    params = {
        "latitude": latitude,
        "longitude": longitude,
        "start_date": start_date,
        "end_date": end_date,
        "daily": ",".join(daily_variables),
        "timezone": timezone,
        "models": model,
    }
    return f"{OPEN_METEO_ARCHIVE_URL}?{urlencode(params)}"


def fetch_json(
    url: str,
    *,
    user_agent: str = DEFAULT_USER_AGENT,
    timeout: int = 60,
    delay_seconds: float = 0.0,
    cache_path: Path | None = None,
) -> dict[str, Any]:
    if cache_path is not None and cache_path.exists():
        return load_json(cache_path)

    if delay_seconds > 0:
        time.sleep(delay_seconds)

    request = Request(url, headers={"User-Agent": user_agent})
    with urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if cache_path is not None:
        write_json(cache_path, payload)
    return payload


def is_retryable_weather_error(exc: Exception) -> bool:
    if isinstance(exc, HTTPError):
        return exc.code in {408, 409, 425, 429, 500, 502, 503, 504}
    return isinstance(exc, (URLError, TimeoutError, JSONDecodeError))


def get_weather_retry_delay(exc: Exception, default_seconds: float) -> float:
    delay_seconds = max(default_seconds, 0.0)
    if isinstance(exc, HTTPError):
        retry_after = exc.headers.get("Retry-After") if exc.headers is not None else None
        if retry_after:
            try:
                return max(float(retry_after), delay_seconds)
            except ValueError:
                pass
        if exc.code == 429:
            return max(60.0, delay_seconds)
        if exc.code in {500, 502, 503, 504}:
            return max(10.0, delay_seconds)
    return delay_seconds


def flatten_open_meteo_hourly_rows(
    *,
    merged_window: dict[str, Any],
    payload: dict[str, Any],
) -> list[dict[str, Any]]:
    hourly = payload.get("hourly") or {}
    times = hourly.get("time") or []
    variable_names = [name for name in hourly.keys() if name != "time"]
    rows: list[dict[str, Any]] = []
    for index, raw_time in enumerate(times):
        row = {
            "mergedWindowId": merged_window["mergedWindowId"],
            "targetId": merged_window["targetId"],
            "timeLocal": raw_time,
            "timezone": payload.get("timezone"),
            "resolvedLatitude": payload.get("latitude"),
            "resolvedLongitude": payload.get("longitude"),
            "resolvedElevation": payload.get("elevation"),
        }
        for name in variable_names:
            values = hourly.get(name) or []
            row[name] = values[index] if index < len(values) else None
        rows.append(row)
    return rows


def flatten_open_meteo_daily_rows(
    *,
    merged_window: dict[str, Any],
    payload: dict[str, Any],
) -> list[dict[str, Any]]:
    daily = payload.get("daily") or {}
    times = daily.get("time") or []
    variable_names = [name for name in daily.keys() if name != "time"]
    rows: list[dict[str, Any]] = []
    for index, raw_date in enumerate(times):
        row = {
            "mergedWindowId": merged_window["mergedWindowId"],
            "targetId": merged_window["targetId"],
            "date": raw_date,
            "timezone": payload.get("timezone"),
            "resolvedLatitude": payload.get("latitude"),
            "resolvedLongitude": payload.get("longitude"),
            "resolvedElevation": payload.get("elevation"),
        }
        for name in variable_names:
            values = daily.get(name) or []
            row[name] = values[index] if index < len(values) else None
        rows.append(row)
    return rows


def extract_hourly_feature_window(hourly_rows: list[dict[str, Any]], end_local: str, hours: int) -> list[dict[str, Any]]:
    end_time = datetime.fromisoformat(end_local)
    start_time = end_time - timedelta(hours=hours)
    return [row for row in hourly_rows if start_time < datetime.fromisoformat(row["timeLocal"]) <= end_time]


def compute_precipitation_features(hourly_rows: list[dict[str, Any]], end_local: str) -> dict[str, Any]:
    features: dict[str, Any] = {}
    precipitation_key = "precipitation"

    for hours, feature_name in ((6, "precip_6h_mm"), (12, "precip_12h_mm"), (24, "precip_24h_mm"), (48, "precip_48h_mm"), (72, "precip_72h_mm"), (24 * 7, "precip_7d_mm")):
        window = extract_hourly_feature_window(hourly_rows, end_local, hours)
        features[feature_name] = round(sum(float(row.get(precipitation_key) or 0.0) for row in window), 3)

    for hours, feature_name in ((1, "max_precip_1h_mm"), (3, "max_precip_3h_mm"), (6, "max_precip_6h_mm"), (12, "max_precip_12h_mm")):
        window = extract_hourly_feature_window(hourly_rows, end_local, hours)
        rolling_values = [float(row.get(precipitation_key) or 0.0) for row in window]
        if hours == 1:
            features[feature_name] = round(max(rolling_values, default=0.0), 3)
            continue
        best = 0.0
        for index in range(len(rolling_values)):
            best = max(best, sum(rolling_values[max(0, index - hours + 1):index + 1]))
        features[feature_name] = round(best, 3)

    thresholds = ((1.0, "hours_since_precip_over_1mm"), (5.0, "hours_since_precip_over_5mm"), (10.0, "hours_since_precip_over_10mm"))
    sorted_rows = sorted(hourly_rows, key=lambda row: row["timeLocal"])
    end_time = datetime.fromisoformat(end_local)
    for threshold, feature_name in thresholds:
        last_time: datetime | None = None
        for row in sorted_rows:
            current_time = datetime.fromisoformat(row["timeLocal"])
            if current_time > end_time:
                break
            if float(row.get(precipitation_key) or 0.0) >= threshold:
                last_time = current_time
        features[feature_name] = int((end_time - last_time).total_seconds() // 3600) if last_time is not None else None

    antecedent_window = extract_hourly_feature_window(hourly_rows, end_local, 24 * 7)
    api_value = 0.0
    decay = 0.85
    for row in sorted(antecedent_window, key=lambda item: item["timeLocal"]):
        api_value = api_value * decay + float(row.get(precipitation_key) or 0.0)
    features["antecedent_precipitation_index"] = round(api_value, 3)
    return features


def compute_daily_precipitation_features(daily_rows: list[dict[str, Any]], observation_date: str) -> dict[str, Any]:
    features: dict[str, Any] = {
        "precip_prev_day_mm": None,
        "precip_2d_mm": 0.0,
        "precip_3d_mm": 0.0,
        "precip_5d_mm": 0.0,
        "precip_7d_mm": 0.0,
        "precip_10d_mm": 0.0,
        "precip_14d_mm": 0.0,
        "precip_21d_mm": 0.0,
        "precip_30d_mm": 0.0,
        "max_daily_precip_3d_mm": 0.0,
        "max_daily_precip_7d_mm": 0.0,
        "max_daily_precip_14d_mm": 0.0,
        "max_cell_precip_prev_day_mm": None,
        "max_cell_precip_3d_mm": 0.0,
        "max_cell_precip_7d_mm": 0.0,
        "max_cell_precip_14d_mm": 0.0,
        "max_cell_precip_30d_mm": 0.0,
        "p90_cell_precip_prev_day_mm": None,
        "p90_cell_precip_3d_mm": 0.0,
        "p90_cell_precip_7d_mm": 0.0,
        "p90_cell_precip_14d_mm": 0.0,
        "p90_cell_precip_30d_mm": 0.0,
        "spatial_precip_amplification_3d": None,
        "spatial_precip_amplification_7d": None,
        "spatial_precip_amplification_14d": None,
        "spatial_precip_amplification_30d": None,
        "weatherGridCellCount": 1.0,
        "weatherGridAvailableCellCount": 1.0,
        "weatherGridHasWatershed": 0.0,
        "weatherGridCoveredAreaFraction": None,
        "weatherGridAvailableWeightFractionPrevDay": None,
        "weatherGridAvailableWeightFractionMean30d": None,
        "weatherGridAvailableWeightFractionMin30d": None,
        "wet_days_7d": 0,
        "wet_days_14d": 0,
        "wet_days_30d": 0,
        "days_since_precip_over_1mm": None,
        "days_since_precip_over_5mm": None,
        "days_since_precip_over_10mm": None,
        "antecedent_precipitation_index_daily": 0.0,
        "antecedent_precipitation_index_daily_70": 0.0,
        "antecedent_precipitation_index_daily_85": 0.0,
        "antecedent_precipitation_index_daily_93": 0.0,
        "rain_prev_day_mm": None,
        "rain_3d_mm": 0.0,
        "rain_7d_mm": 0.0,
        "snowfall_prev_day_cm": None,
        "snowfall_3d_cm": 0.0,
        "snowfall_7d_cm": 0.0,
        "snowfall_14d_cm": 0.0,
        "temperature2mMeanPrevDay": None,
        "temperature2mMinPrevDay": None,
        "temperature2mMaxPrevDay": None,
        "temperature2mMean_3d": None,
        "temperature2mMean_7d": None,
        "temperature2mMean_14d": None,
        "positive_degree_days_3d": 0.0,
        "positive_degree_days_7d": 0.0,
        "positive_degree_days_14d": 0.0,
        "precipitation_hours_3d": 0.0,
        "precipitation_hours_7d": 0.0,
        "precipitation_hours_14d": 0.0,
    }

    observation_day = date.fromisoformat(observation_date)
    end_day = observation_day - timedelta(days=1)
    sorted_rows = sorted(daily_rows, key=lambda row: row["date"])
    eligible_rows = [row for row in sorted_rows if date.fromisoformat(row["date"]) <= end_day]
    if not eligible_rows:
        return features

    precip_by_day = [float(row.get("precipitation_sum") or 0.0) for row in eligible_rows]
    rain_by_day = [float(row.get("rain_sum") or 0.0) for row in eligible_rows]
    snowfall_by_day = [float(row.get("snowfall_sum") or 0.0) for row in eligible_rows]
    temperature_mean_by_day = [row.get("temperature_2m_mean") for row in eligible_rows]
    temperature_min_by_day = [row.get("temperature_2m_min") for row in eligible_rows]
    temperature_max_by_day = [row.get("temperature_2m_max") for row in eligible_rows]
    precipitation_hours_by_day = [float(row.get("precipitation_hours") or 0.0) for row in eligible_rows]
    max_cell_precip_by_day = [float(row.get("max_cell_precipitation_sum") if row.get("max_cell_precipitation_sum") is not None else row.get("precipitation_sum") or 0.0) for row in eligible_rows]
    p90_cell_precip_by_day = [float(row.get("p90_cell_precipitation_sum") if row.get("p90_cell_precipitation_sum") is not None else row.get("precipitation_sum") or 0.0) for row in eligible_rows]
    available_weight_by_day = [row.get("weatherGridAvailableWeightFraction") for row in eligible_rows]

    def trailing_sum(values: list[float], days: int) -> float:
        return round(sum(values[-days:]), 3)

    def trailing_max(values: list[float], days: int) -> float:
        return round(max(values[-days:], default=0.0), 3)

    def trailing_count(values: list[float], days: int, *, threshold: float) -> int:
        return sum(1 for value in values[-days:] if value >= threshold)

    def trailing_mean(values: list[Any], days: int) -> float | None:
        trailing_values = [float(value) for value in values[-days:] if value is not None]
        if not trailing_values:
            return None
        return round(sum(trailing_values) / len(trailing_values), 3)

    def trailing_positive_degree_days(values: list[Any], days: int) -> float:
        trailing_values = [max(float(value), 0.0) for value in values[-days:] if value is not None]
        return round(sum(trailing_values), 3)

    def safe_ratio(numerator: float | None, denominator: float | None) -> float | None:
        if numerator is None or denominator is None or abs(denominator) < 1e-9:
            return None
        return round(numerator / denominator, 6)

    if precip_by_day:
        features["precip_prev_day_mm"] = round(precip_by_day[-1], 3)
        features["precip_2d_mm"] = trailing_sum(precip_by_day, 2)
        features["precip_3d_mm"] = trailing_sum(precip_by_day, 3)
        features["precip_5d_mm"] = trailing_sum(precip_by_day, 5)
        features["precip_7d_mm"] = trailing_sum(precip_by_day, 7)
        features["precip_10d_mm"] = trailing_sum(precip_by_day, 10)
        features["precip_14d_mm"] = trailing_sum(precip_by_day, 14)
        features["precip_21d_mm"] = trailing_sum(precip_by_day, 21)
        features["precip_30d_mm"] = trailing_sum(precip_by_day, 30)
        features["max_daily_precip_3d_mm"] = trailing_max(precip_by_day, 3)
        features["max_daily_precip_7d_mm"] = trailing_max(precip_by_day, 7)
        features["max_daily_precip_14d_mm"] = trailing_max(precip_by_day, 14)
        features["max_cell_precip_prev_day_mm"] = round(max_cell_precip_by_day[-1], 3)
        features["max_cell_precip_3d_mm"] = trailing_sum(max_cell_precip_by_day, 3)
        features["max_cell_precip_7d_mm"] = trailing_sum(max_cell_precip_by_day, 7)
        features["max_cell_precip_14d_mm"] = trailing_sum(max_cell_precip_by_day, 14)
        features["max_cell_precip_30d_mm"] = trailing_sum(max_cell_precip_by_day, 30)
        features["p90_cell_precip_prev_day_mm"] = round(p90_cell_precip_by_day[-1], 3)
        features["p90_cell_precip_3d_mm"] = trailing_sum(p90_cell_precip_by_day, 3)
        features["p90_cell_precip_7d_mm"] = trailing_sum(p90_cell_precip_by_day, 7)
        features["p90_cell_precip_14d_mm"] = trailing_sum(p90_cell_precip_by_day, 14)
        features["p90_cell_precip_30d_mm"] = trailing_sum(p90_cell_precip_by_day, 30)
        features["spatial_precip_amplification_3d"] = safe_ratio(features["max_cell_precip_3d_mm"], features["precip_3d_mm"])
        features["spatial_precip_amplification_7d"] = safe_ratio(features["max_cell_precip_7d_mm"], features["precip_7d_mm"])
        features["spatial_precip_amplification_14d"] = safe_ratio(features["max_cell_precip_14d_mm"], features["precip_14d_mm"])
        features["spatial_precip_amplification_30d"] = safe_ratio(features["max_cell_precip_30d_mm"], features["precip_30d_mm"])
        features["wet_days_7d"] = trailing_count(precip_by_day, 7, threshold=0.1)
        features["wet_days_14d"] = trailing_count(precip_by_day, 14, threshold=0.1)
        features["wet_days_30d"] = trailing_count(precip_by_day, 30, threshold=0.1)

    previous_day_row = eligible_rows[-1]
    features["weatherGridCellCount"] = previous_day_row.get("weatherGridCellCount", 1.0)
    features["weatherGridAvailableCellCount"] = previous_day_row.get("weatherGridAvailableCellCount", features["weatherGridCellCount"])
    features["weatherGridHasWatershed"] = previous_day_row.get("weatherGridHasWatershed", 0.0)
    features["weatherGridCoveredAreaFraction"] = previous_day_row.get("weatherGridCoveredAreaFraction")
    features["weatherGridAvailableWeightFractionPrevDay"] = previous_day_row.get("weatherGridAvailableWeightFraction")
    availability_values = [float(value) for value in available_weight_by_day[-30:] if value is not None]
    if availability_values:
        features["weatherGridAvailableWeightFractionMean30d"] = round(sum(availability_values) / len(availability_values), 6)
        features["weatherGridAvailableWeightFractionMin30d"] = round(min(availability_values), 6)
    features["rain_prev_day_mm"] = (
        round(float(previous_day_row.get("rain_sum") or 0.0), 3) if previous_day_row.get("rain_sum") is not None else None
    )
    features["snowfall_prev_day_cm"] = (
        round(float(previous_day_row.get("snowfall_sum") or 0.0), 3)
        if previous_day_row.get("snowfall_sum") is not None
        else None
    )
    features["temperature2mMeanPrevDay"] = previous_day_row.get("temperature_2m_mean")
    features["temperature2mMinPrevDay"] = previous_day_row.get("temperature_2m_min")
    features["temperature2mMaxPrevDay"] = previous_day_row.get("temperature_2m_max")
    features["rain_3d_mm"] = trailing_sum(rain_by_day, 3)
    features["rain_7d_mm"] = trailing_sum(rain_by_day, 7)
    features["snowfall_3d_cm"] = trailing_sum(snowfall_by_day, 3)
    features["snowfall_7d_cm"] = trailing_sum(snowfall_by_day, 7)
    features["snowfall_14d_cm"] = trailing_sum(snowfall_by_day, 14)
    features["temperature2mMean_3d"] = trailing_mean(temperature_mean_by_day, 3)
    features["temperature2mMean_7d"] = trailing_mean(temperature_mean_by_day, 7)
    features["temperature2mMean_14d"] = trailing_mean(temperature_mean_by_day, 14)
    features["positive_degree_days_3d"] = trailing_positive_degree_days(temperature_mean_by_day, 3)
    features["positive_degree_days_7d"] = trailing_positive_degree_days(temperature_mean_by_day, 7)
    features["positive_degree_days_14d"] = trailing_positive_degree_days(temperature_mean_by_day, 14)
    features["precipitation_hours_3d"] = trailing_sum(precipitation_hours_by_day, 3)
    features["precipitation_hours_7d"] = trailing_sum(precipitation_hours_by_day, 7)
    features["precipitation_hours_14d"] = trailing_sum(precipitation_hours_by_day, 14)

    thresholds = (
        (1.0, "days_since_precip_over_1mm"),
        (5.0, "days_since_precip_over_5mm"),
        (10.0, "days_since_precip_over_10mm"),
    )
    for threshold, feature_name in thresholds:
        last_match_day: date | None = None
        for row in eligible_rows:
            current_day = date.fromisoformat(row["date"])
            if float(row.get("precipitation_sum") or 0.0) >= threshold:
                last_match_day = current_day
        features[feature_name] = (end_day - last_match_day).days if last_match_day is not None else None

    api_values = {
        "antecedent_precipitation_index_daily_70": 0.0,
        "antecedent_precipitation_index_daily_85": 0.0,
        "antecedent_precipitation_index_daily_93": 0.0,
    }
    for row in eligible_rows[-30:]:
        precipitation_value = float(row.get("precipitation_sum") or 0.0)
        api_values["antecedent_precipitation_index_daily_70"] = api_values["antecedent_precipitation_index_daily_70"] * 0.70 + precipitation_value
        api_values["antecedent_precipitation_index_daily_85"] = api_values["antecedent_precipitation_index_daily_85"] * 0.85 + precipitation_value
        api_values["antecedent_precipitation_index_daily_93"] = api_values["antecedent_precipitation_index_daily_93"] * 0.93 + precipitation_value
    for feature_name, value in api_values.items():
        features[feature_name] = round(value, 3)
    features["antecedent_precipitation_index_daily"] = features["antecedent_precipitation_index_daily_85"]
    return features
