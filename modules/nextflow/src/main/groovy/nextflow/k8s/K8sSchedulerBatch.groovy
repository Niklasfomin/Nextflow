package nextflow.k8s;

import nextflow.k8s.client.K8sSchedulerClient;
import nextflow.processor.SchedulerBatch;

class K8sSchedulerBatch extends SchedulerBatch {

    private K8sSchedulerClient schedulerClient

    K8sSchedulerBatch( int batchSize ) {
        super(batchSize)
    }

    void setSchedulerClient( K8sSchedulerClient schedulerClient ) {
        this.schedulerClient = schedulerClient
    }

    void startBatchImpl() {
        schedulerClient.startBatch()
    }

    void endBatch() {
        schedulerClient.endBatch()
    }
}
