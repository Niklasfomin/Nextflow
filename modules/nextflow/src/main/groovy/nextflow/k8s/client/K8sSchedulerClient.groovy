/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.k8s.client

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.dag.DAG
import nextflow.exception.NodeTerminationException
import nextflow.k8s.K8sConfig

import nextflow.k8s.model.PodHostMount
import nextflow.k8s.model.PodSecurityContext
import nextflow.k8s.model.PodSpecBuilder
import nextflow.k8s.model.PodVolumeClaim

import java.nio.file.Paths

/**
 * K8sScheduler API client
 *
 * @author Fabian Lehmann <fabian.lehmann@informatik.hu-berlin.de>
 */
@Slf4j
class K8sSchedulerClient {

    private final K8sConfig.K8sScheduler schedulerConfig
    private final String namespace
    private final String runName
    private boolean registered = false
    private boolean closed = false
    private final K8sClient k8sClient
    private final Collection<PodVolumeClaim> volumeClaims
    private String ip
    private int tasksInBatch = 0;

    private DAG dag
    Object submittedVertexHelper = new Object()
    int submittedVertices = 0



    K8sSchedulerClient(K8sConfig.K8sScheduler schedulerConfig, String namespace, String runName, K8sClient k8sClient,
                       Collection<PodVolumeClaim> volumeClaims) {
        this.volumeClaims = volumeClaims
        this.k8sClient = k8sClient
        this.schedulerConfig = schedulerConfig
        this.namespace = namespace ?: 'default'
        this.runName = runName
    }

    private String getDNS(){
        return "http://${ip.replace('.','-')}.${namespace}.pod.cluster.local:${schedulerConfig.getPort()}/v1/"
    }

    private void startScheduler(){

        boolean start = false
        Map state

        try{
            //If no pod with the name exists an exceptions is thrown
            state = k8sClient.podState( schedulerConfig.getName() )
            if( state.terminated ) {
                k8sClient.podDelete( schedulerConfig.getName() )
                start = true
                log.info "Scheduler ${schedulerConfig.getName()} is terminated"
            } else if( state.running || state.waiting ) log.trace "Scheduler ${schedulerConfig.getName()} is already running"
            else log.error "Unknown state for ${schedulerConfig.getName()}: ${state.toString()}"

        } catch ( K8sResponseException e ) {
            if ( e.getErrorCode() == 404 ) start = true
            else log.error( "Got unexpected HTTP code ${e.getErrorCode()} while checking scheduler's state", e.message )
        } catch ( NodeTerminationException e ){
            //NodeTerminationException is thrown if pod is not found
            start = true
            log.info "Scheduler ${schedulerConfig.getName()} can not be found"
        }

        if( start ){
            log.trace "Scheduler ${schedulerConfig.getName()} is not running, let's start"
            final builder = new PodSpecBuilder()
                    .withImageName( schedulerConfig.getContainer() )
                    .withPodName( schedulerConfig.getName() )
                    .withCpus( schedulerConfig.getCPUs() )
                    .withMemory( schedulerConfig.getMemory() )
                    .withImagePullPolicy( schedulerConfig.getImagePullPolicy() )
                    .withServiceAccount( schedulerConfig.getServiceAccount() )
                    .withNamespace( namespace )
                    .withLabel('component', 'scheduler')
                    .withLabel('tier', 'control-plane')
                    .withLabel('app', 'nextflow')
                    .withVolumeClaims( volumeClaims )

            if( schedulerConfig.getNodeSelector() )
                builder.setNodeSelector( schedulerConfig.getNodeSelector() )

            if ( schedulerConfig.getWorkDir() )
                builder.withWorkDir( schedulerConfig.getWorkDir() )

            if( schedulerConfig.getCommand() )
                builder.withCommand( schedulerConfig.getCommand() )

            if( schedulerConfig.runAsUser() != null ){
                builder.securityContext = new PodSecurityContext( schedulerConfig.runAsUser() )
            }

            Map pod = builder.build()

            List env = [[
                    name: 'SCHEDULER_NAME',
                    value: schedulerConfig.getName()
            ],[
                    name: 'AUTOCLOSE',
                    value: schedulerConfig.autoClose() as String
            ]]

            Map container = pod.spec.containers.get(0) as Map
            container.put('env', env)

            k8sClient.podCreate( pod, Paths.get('.nextflow-scheduler.yaml'), namespace)
        }

        //wait for scheduler to get ready
        def i = 0
        do {
            sleep(100)
            state = k8sClient.podState( schedulerConfig.getName() )
            //log state every 2 seconds
            if( i++ % 20 ) log.trace "Waiting for scheduler to start, current state: ${state.toString()}"
        } while ( state.waiting || state.isEmpty() );

        ip = k8sClient.podIP( schedulerConfig.getName() )
        if( !state.running ) throw new IllegalStateException( "Scheduler pod ${schedulerConfig.getName()} was not started, state: ${state.toString()}" )

    }

    synchronized void registerScheduler( Map data ) {
        if ( registered ) return

        dag = (Global.session as Session).getDag()

        startScheduler()

        String url = "${getDNS()}/scheduler/$runName"
        registered = true;
        int trials = 0
        while ( trials++ < 50 ) {
            try {
                HttpURLConnection post = new URL(url).openConnection() as HttpURLConnection
                post.setRequestMethod( "POST" )
                post.setDoOutput(true)
                post.setRequestProperty("Content-Type", "application/json")
                data.dns = getDNS()
                data.namespace = namespace
                data.strategy = schedulerConfig.getStrategy()
                String message = JsonOutput.toJson( data )
                post.getOutputStream().write(message.getBytes("UTF-8"))
                int responseCode = post.getResponseCode()
                if( responseCode != 200 ){
                    throw new IllegalStateException( "Got code: ${responseCode} from k8s scheduler while registering" )
                }
                loadDAG()
                return
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("The scheduler was not found under '$url', is the url correct and the scheduler running?")
            } catch (ConnectException e) {
                Thread.sleep( 3000 )
            }catch (IOException e) {
                throw new IllegalStateException("Cannot register scheduler under $url, got ${e.class.toString()}: ${e.getMessage()}", e)
            }
        }
        throw new IllegalStateException("Cannot connect to scheduler under $url" )
    }

    synchronized void closeScheduler(){
        if ( closed ) return
        closed = true;
        HttpURLConnection post = new URL("${getDNS()}/scheduler/$runName").openConnection() as HttpURLConnection
        post.setRequestMethod( "DELETE" )
        int responseCode = post.getResponseCode()
        log.trace "Delete scheduler code was: ${responseCode}"
    }

    Map registerTask( Map config, int id ){

        HttpURLConnection post = new URL("${getDNS()}/scheduler/$runName/task/$id").openConnection() as HttpURLConnection
        post.setRequestMethod( "POST" )
        String message = JsonOutput.toJson( config )
        post.setDoOutput(true)
        post.setRequestProperty("Content-Type", "application/json")
        post.getOutputStream().write(message.getBytes("UTF-8"));
        int responseCode = post.getResponseCode()
        if( responseCode != 200 ){
            throw new IllegalStateException( "Got code: ${responseCode} from nextflow scheduler, while registering task: ${config.name}" )
        }
        tasksInBatch++
        Map response = new JsonSlurper().parse(post.getInputStream()) as Map
        return response

    }

    private void batch( String command ){
        HttpURLConnection put = new URL("${getDNS()}/scheduler/$runName/${command}Batch").openConnection() as HttpURLConnection
        put.setRequestMethod( "PUT" )
        if ( command == 'end' ){
            put.setDoOutput(true)
            put.setRequestProperty("Content-Type", "application/json")
            put.getOutputStream().write("$tasksInBatch".getBytes("UTF-8"));
        }
        int responseCode = put.getResponseCode()
        if( responseCode != 200 ){
            throw new IllegalStateException( "Got code: ${responseCode} from nextflow scheduler, while ${command}ing batch" )
        }
    }

    void startBatch(){
        tasksInBatch = 0
        if ( !closed ) batch('start')
    }

    void endBatch(){
        if ( !closed ) batch('end')
    }

    Map getTaskState( int id ){

        HttpURLConnection get = new URL("${getDNS()}/scheduler/$runName/task/$id").openConnection() as HttpURLConnection
        get.setRequestMethod( "GET" )
        get.setDoOutput(true)
        int responseCode = get.getResponseCode()
        if( responseCode != 200 ){
            throw new IllegalStateException( "Got code: ${responseCode} from nextflow scheduler, while requesting task state: $podname" )
        }
        Map response = new JsonSlurper().parse(get.getInputStream()) as Map
        return response

    }


    ///* DAG */

    private submitVertices( List vertices ){
        HttpURLConnection put = new URL("${getDNS()}/scheduler/$runName/DAG/vertices").openConnection() as HttpURLConnection
        put.setRequestMethod( "POST" )
        String message = JsonOutput.toJson( vertices )
        put.setDoOutput(true)
        put.setRequestProperty("Content-Type", "application/json")
        put.getOutputStream().write(message.getBytes("UTF-8"));
        int responseCode = put.getResponseCode()
        if( responseCode != 200 ){
            throw new IllegalStateException( "Got code: ${responseCode} from nextflow scheduler, while submitting vertices: ${vertices}" )
        }
    }

    private submitEdges( List edges ){
        HttpURLConnection put = new URL("${getDNS()}/scheduler/$runName/DAG/edges").openConnection() as HttpURLConnection
        put.setRequestMethod( "POST" )
        String message = JsonOutput.toJson( edges )
        put.setDoOutput(true)
        put.setRequestProperty("Content-Type", "application/json")
        put.getOutputStream().write(message.getBytes("UTF-8"));
        int responseCode = put.getResponseCode()
        if( responseCode != 200 ){
            throw new IllegalStateException( "Got code: ${responseCode} from nextflow scheduler, while submitting vertices: ${edges}" )
        }
    }

    private loadDAG(){
        informDagChange( (Global.session as Session).dag.getProcessedVertices() )
    }

    void informDagChange( List<DAG.Vertex> vertices ) {
        Set verticesToSubmit = []
        List edgesToSubmit
        synchronized ( submittedVertexHelper ){
            if ( vertices.size() <= submittedVertices ) return
            for ( int i = submittedVertices; i < vertices.size(); i++ ) {
                verticesToSubmit << vertices[ i ]
            }
            edgesToSubmit = dag.getEdges().findAll {
                it.to && it.from && ( verticesToSubmit.contains( it.to ) || verticesToSubmit.contains( it.from ) )
            }
            submittedVertices += verticesToSubmit.size()
        }
        verticesToSubmit = verticesToSubmit.collect { extractVertex( it) }
        edgesToSubmit = edgesToSubmit.collect { extractEdge( it ) }
        submitVertices( verticesToSubmit as List )
        submitEdges( edgesToSubmit )
    }

    private extractVertex( DAG.Vertex v ){
        return [
                label : v.label,
                type : v.type.toString(),
                uid : v.getId()
        ]
    }

    private extractEdge( DAG.Edge e ){
        return [
                uid : e.getId(),
                label : e.getLabel(),
                from : e.getFrom().getId(),
                to : e.getTo().getId()
        ]
    }
}
