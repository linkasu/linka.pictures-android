package su.linka.pictures;

public abstract class Callback<T> {

    public abstract void onDone(T result);
    public abstract void onFail(Exception error);
}
