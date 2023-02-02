package nextflow.processor

abstract class SchedulerBatch {

    private final int batchSize
    private int currentlySubmitted = 0

    SchedulerBatch(int batchSize) {
        assert batchSize > 1
        this.batchSize = batchSize
    }

    void startBatch() {
        currentlySubmitted = 0
        startBatchImpl()
    }

    abstract protected void startBatchImpl()

    abstract void endBatch()

    default void startSubmit() {
        if ( ++currentlySubmitted > batchSize ) {
            endBatch()
            startBatchImpl()
            currentlySubmitted = 1
        }
    }

}