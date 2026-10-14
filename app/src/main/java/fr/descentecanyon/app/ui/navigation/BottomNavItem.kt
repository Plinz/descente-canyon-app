package fr.descentecanyon.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import fr.descentecanyon.app.core.ui.R

enum class BottomNavItem(@param:StringRes val labelResId: Int, val icon: ImageVector, val screen: Screen) {
    HOME(R.string.tab_home, Icons.Default.Home, Screen.Home),
    SEARCH(R.string.tab_search, Icons.Default.Search, Screen.Search),
    MAP(R.string.tab_map, Icons.Default.Explore, Screen.Map),
    FAVORITES(R.string.tab_favorites, Icons.Default.Favorite, Screen.Favorites),
}
