package ca.mcgill.distsys.hbase.indexcoprocessorsinmem;

import ca.mcgill.distsys.hbase96.indexcommonsinmem.SecondaryIndexConstants;
import ca.mcgill.distsys.hbase96.indexcommonsinmem.Util;
import ca.mcgill.distsys.hbase96.indexcommonsinmem.proto.Column;
import ca.mcgill.distsys.hbase96.indexcommonsinmem.proto.Criterion;
import ca.mcgill.distsys.hbase96.indexcommonsinmem.proto.IndexedColumnQuery;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.IndexCoprocessorCreateRequest;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.IndexCoprocessorCreateResponse;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.IndexCoprocessorDeleteRequest;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.IndexCoprocessorDeleteResponse;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.IndexCoprocessorInMemService;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.IndexedQueryRequest;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.IndexedQueryResponse;
import ca.mcgill.distsys.hbase96.indexcoprocessorsinmem.protobuf.generated.IndexCoprocessorInMem.ProtoResult;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class IndexCoprocessorInMemEndpoint extends IndexCoprocessorInMemService implements CoprocessorService, Coprocessor {

    private static final Log LOG = LogFactory.getLog(IndexCoprocessorInMemEndpoint.class);
    private byte[] tableName;
    private boolean doNotRun = false;
    private Configuration configuration;
    private HRegion region;

    @Override
    public void createIndex(RpcController controller, IndexCoprocessorCreateRequest request, RpcCallback<IndexCoprocessorCreateResponse> done) {
        IndexCoprocessorCreateResponse.Builder builder = IndexCoprocessorCreateResponse.newBuilder();
        builder.setSuccess(true);
        if (!doNotRun) {
            // TODO check request inputs (family & qualifier)
            try {
                RegionIndexMap rim = RegionIndexMap.getInstance();
                RegionIndex regionIndex = rim.get(region.getRegionNameAsString());

                if (regionIndex == null) {
                    int maxTreeSize = configuration.getInt(SecondaryIndexConstants.PRIMARYKEY_TREE_MAX_SIZE,
                            SecondaryIndexConstants.PRIMARYKEY_TREE_MAX_SIZE_DEFAULT);
                    regionIndex = new RegionIndex(maxTreeSize);
                    rim.add(region.getRegionNameAsString(), regionIndex);
                }

                byte[] idxColKey = Util.concatByteArray(request.getFamily().toByteArray(), request.getQualifier().toByteArray());
                if (!regionIndex.getIndexedColumns().contains(idxColKey)) {
                    regionIndex.add(request.getFamily().toByteArray(), request.getQualifier().toByteArray(), region);
                } else {
                    LOG.info("INDEX: requested index already exists.");
                }
            } catch (Exception e) {
                LOG.error("INDEX: Failed to create index.", e);
                builder.setSuccess(false);
                ResponseConverter.setControllerException(controller, new IOException(e));
            }
        }
        done.run(builder.build());
    }

    @Override
    public void deleteIndex(RpcController controller, IndexCoprocessorDeleteRequest request, RpcCallback<IndexCoprocessorDeleteResponse> done) {
        IndexCoprocessorDeleteResponse.Builder builder = IndexCoprocessorDeleteResponse.newBuilder();
        builder.setSuccess(true);
        if (!doNotRun) {
            // TODO check request inputs (family & qualifier)
            try {
                RegionIndexMap rim = RegionIndexMap.getInstance();
                RegionIndex regionIndex = rim.get(region.getRegionNameAsString());
                if (regionIndex != null) {
                    regionIndex.remove(request.getFamily().toByteArray(), request.getQualifier().toByteArray());
                }
            } catch (Exception e) {
                builder.setSuccess(false);
                ResponseConverter.setControllerException(controller, new IOException(e));
            }
        }
        done.run(builder.build());
    }

    public void start(CoprocessorEnvironment environment) throws IOException {
        // make sure we are on a region server
        if (!(environment instanceof RegionCoprocessorEnvironment)) {
            throw new IllegalArgumentException("Indexes only act on regions - started in an environment that was not a region");
        }

        RegionCoprocessorEnvironment env = (RegionCoprocessorEnvironment) environment;
        region = env.getRegion();
        HTableDescriptor desc = region.getTableDesc();

        tableName = desc.getName();
        if (Bytes.toString(tableName).equals("-ROOT-") || Bytes.toString(tableName).equalsIgnoreCase(".META.")
                || Bytes.toString(tableName).equals(SecondaryIndexConstants.MASTER_INDEX_TABLE_NAME)) {
            doNotRun = true;
        } else {
            configuration = HBaseConfiguration.create();
        }

    }

    @Override
    public void execIndexedQuery(RpcController controller, IndexedQueryRequest request, RpcCallback<IndexedQueryResponse> done) {
        RegionIndexMap rim = RegionIndexMap.getInstance();
        RegionIndex regionIndex = rim.get(region.getRegionNameAsString());

        IndexedQueryResponse.Builder builder = IndexedQueryResponse.newBuilder();

        if (!doNotRun && regionIndex != null) {
            IndexedColumnQuery query = Util.buildQuery(request);
            if (!query.getCriteria().isEmpty()) {
                List<Criterion<?>> criteriaOnNonIndexedColumns = new ArrayList<Criterion<?>>();
                List<Criterion<?>> criteriaOnIndexColumns = new ArrayList<Criterion<?>>();
                splitCriteriaByTarget(criteriaOnNonIndexedColumns, criteriaOnIndexColumns, query.getCriteria(), regionIndex);
                if (!criteriaOnIndexColumns.isEmpty()) {
                    try {
                        List<ProtoResult> filteredRows = regionIndex.filterRowsFromCriteria(criteriaOnIndexColumns, criteriaOnNonIndexedColumns, query,
                                region);
                        builder.addAllResult(filteredRows);
                        
                    } catch (Exception e) {
                        ResponseConverter.setControllerException(controller, new IOException(e));
                    }
                } else {
                    // At least one criteria must apply to an indexed
                    // column, exception otherwise
                    ResponseConverter.setControllerException(controller, new IOException(
                            "An indexed query must contains at least 1 criterion that applies to an indexed column."));
                }

            } else {
                // The query must contain at least 1 criterion, exception
                // otherwise
                ResponseConverter.setControllerException(controller, new IOException("An indexed query must contains at least 1 criterion."));
            }

        } else {
            // No index exists for this region, exception
            ResponseConverter.setControllerException(controller, new IOException("No index exists for table ["
                    + region.getTableDesc().getNameAsString() + "]."));
        }

        done.run(builder.build());
    }

    private void splitCriteriaByTarget(List<Criterion<?>> criteriaOnNonIndexedColumns, List<Criterion<?>> criteriaOnIndexColumns,
            List<Criterion<?>> criteria, RegionIndex regionIndex) {
        Set<String> indexedColumns = regionIndex.getIndexedColumns();
               
        for (Criterion<?> criterion : criteria) {
            Column column = criterion.getCompareColumn();

            
            if (indexedColumns.contains(Bytes.toString(column.getFamily()) + Bytes.toString(column.getQualifier()))) {
                criteriaOnIndexColumns.add(criterion);
            } else {
                criteriaOnNonIndexedColumns.add(criterion);
            }
        }

    }

    public void stop(CoprocessorEnvironment env) throws IOException {
        // TODO Auto-generated method stub

    }

    public Service getService() {
        return this;
    }

}
