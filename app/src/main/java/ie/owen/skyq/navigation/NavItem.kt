package ie.owen.skyq.navigation

enum class NavItem(val label: String, val route: String) {
    HOME("Home", "home"),
    GUIDE("TV guide", "guide"),
    RECORDINGS("Recordings", "recordings"),
    CATCHUP("Catch up TV", "catchup"),
    SETTINGS("Settings", "settings"),
}
