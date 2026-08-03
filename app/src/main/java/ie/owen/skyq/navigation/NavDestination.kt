package ie.owen.skyq.navigation

sealed class NavDestination {
    data object Guide    : NavDestination()
    data object Settings : NavDestination()
}
