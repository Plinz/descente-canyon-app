package fr.descentecanyon.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import fr.descentecanyon.app.core.ui.R

enum class BottomNavItem(@param:StringRes val labelResId: Int, val icon: ImageVector, val screen: Screen) {
    HOME(R.string.tab_home, Icons.Default.Home, Screen.Home),

    /** The search and the map of the catalog, in one screen. */
    EXPLORE(R.string.tab_explore, Icons.Default.Explore, Screen.Search),

    /** The outings and stays planned, and the logbook. */
    OUTINGS(R.string.tab_outings, Icons.Default.Hiking, Screen.Outings),
    FAVORITES(R.string.tab_favorites, Icons.Default.Favorite, Screen.Favorites),

    /** The account, follows, contributions, data and settings. */
    ACCOUNT(R.string.tab_me, Icons.Default.Person, Screen.MyAccount),
}
