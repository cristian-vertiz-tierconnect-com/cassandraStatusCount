package coderoad;

import com.datastax.driver.core.*;
import com.datastax.driver.core.policies.ConstantReconnectionPolicy;
import com.datastax.driver.core.policies.DowngradingConsistencyRetryPolicy;

public class CassandraUtils
{

    private static Session riotSession = null;
    private static Cluster cluster = null;

    public static Session getSession()
    {
        return riotSession;
    }

    public static void init( String hostin, String keyspace )
    {

        PoolingOptions po = new PoolingOptions();

        po.setMaxConnectionsPerHost(HostDistance.LOCAL, 16);
//		po.setCoreConnectionsPerHost(HostDistance.LOCAL,po.getMaxConnectionsPerHost(HostDistance.LOCAL));
        po.setMaxSimultaneousRequestsPerConnectionThreshold(HostDistance.LOCAL, 128);


        cluster = Cluster.builder().withPoolingOptions(po)
                .withRetryPolicy(DowngradingConsistencyRetryPolicy.INSTANCE)
                .withReconnectionPolicy(new ConstantReconnectionPolicy(2000L)).addContactPoint( hostin ).build();
        cluster.getConfiguration().getSocketOptions().setConnectTimeoutMillis(Integer.MAX_VALUE);
        //cluster.getConfiguration().getPoolingOptions().setMaxConnectionsPerHost(HostDistance.LOCAL,100);

        Metadata metadata = cluster.getMetadata();

        riotSession = cluster.connect( keyspace );


//        cluster = Cluster.builder().addContactPoint( hostin ).build();
//
//		Metadata metadata = cluster.getMetadata();
//		logger.info( "Connected to cluster: " + metadata.getClusterName() );
//
//		for( Host host : metadata.getAllHosts() )
//		{
//			logger.info( String.format( "Datacenter: %s; Host: %s; Rack: %s\n", host.getDatacenter(), host.getAddress(), host.getRack() ) );
//		}
//		riotSession = cluster.connect( keyspace );
    }

    public static void shutdown()
    {
        if (cluster != null)
        {
            cluster.close();
        }
    }
}
