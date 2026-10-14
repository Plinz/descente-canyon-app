package fr.descentecanyon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitCrue
import fr.descentecanyon.app.ui.theme.DebitFilet
import fr.descentecanyon.app.ui.theme.DebitGros
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.DebitSec
import fr.descentecanyon.app.ui.theme.DebitTresGros

fun debitLevelColor(niveau: NiveauDebit): Color = when (niveau) {
    NiveauDebit.SEC -> DebitSec
    NiveauDebit.FILET -> DebitFilet
    NiveauDebit.CORRECT -> DebitCorrect
    NiveauDebit.GROS -> DebitGros
    NiveauDebit.TRES_GROS -> DebitTresGros
    NiveauDebit.CRUE -> DebitCrue
    NiveauDebit.INCONNU -> DebitInconnu
}

@Composable
fun debitLevelLabel(niveau: NiveauDebit): String = when (niveau) {
    NiveauDebit.SEC -> stringResource(R.string.debit_level_sec)
    NiveauDebit.FILET -> stringResource(R.string.debit_level_filet)
    NiveauDebit.CORRECT -> stringResource(R.string.debit_level_correct)
    NiveauDebit.GROS -> stringResource(R.string.debit_level_gros)
    NiveauDebit.TRES_GROS -> stringResource(R.string.debit_level_tres_gros)
    NiveauDebit.CRUE -> stringResource(R.string.debit_level_crue)
    NiveauDebit.INCONNU -> stringResource(R.string.debit_level_inconnu)
}
