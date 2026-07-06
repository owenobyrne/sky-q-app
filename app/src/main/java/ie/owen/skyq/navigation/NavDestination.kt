package ie.owen.skyq.navigation

sealed class NavDestination {
    data object Home     : NavDestination()
    data object Guide    : NavDestination()
    data class  Tag(val uuid: String, val name: String) : NavDestination()
    data object Settings : NavDestination()
}
