package rodozodo.infinite.thread;

public interface Worker {
    boolean runJob();

    default void close() {}
}
