package rodozodo.infinite.client.model;

public interface ModelSink {
    long modelEntry(int model);

    long tints(int first, int count);

    long atlasLevel(int model, int level);
}
