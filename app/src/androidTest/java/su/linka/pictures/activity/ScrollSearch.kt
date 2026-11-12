package su.linka.pictures.activity

/** Search from the current viewport, then reverse once; never scroll indefinitely. */
internal class ScrollSearch(private var backwards: Boolean) {
    private var steps = 0
    private var reversed = false
    private var exhausted = false

    fun advance(perform: (Boolean) -> Boolean): Boolean {
        if (exhausted) return false
        if (steps < 12 && perform(backwards)) {
            steps++
            return true
        }
        if (reversed) exhausted = true
        else {
            backwards = !backwards
            reversed = true
            steps = 0
        }
        return false
    }
}
