package su.linka.pictures

abstract class Callback<T> {
    abstract fun onDone(result: T)
    abstract fun onFail(error: Exception?)
}
