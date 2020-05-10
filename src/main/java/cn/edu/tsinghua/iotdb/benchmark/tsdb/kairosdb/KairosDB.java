package cn.edu.tsinghua.iotdb.benchmark.tsdb.kairosdb;

import cn.edu.tsinghua.iotdb.benchmark.conf.Config;
import cn.edu.tsinghua.iotdb.benchmark.conf.ConfigDescriptor;
import cn.edu.tsinghua.iotdb.benchmark.conf.Constants;
import cn.edu.tsinghua.iotdb.benchmark.measurement.Status;
import cn.edu.tsinghua.iotdb.benchmark.model.KairosDataModel;
import cn.edu.tsinghua.iotdb.benchmark.tsdb.IDatabase;
import cn.edu.tsinghua.iotdb.benchmark.tsdb.TsdbException;
import cn.edu.tsinghua.iotdb.benchmark.utils.HttpRequest;
import cn.edu.tsinghua.iotdb.benchmark.workload.ingestion.Batch;
import cn.edu.tsinghua.iotdb.benchmark.workload.ingestion.Record;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.AggRangeQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.AggRangeValueQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.AggValueQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.GroupByQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.LatestPointQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.PreciseQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.RangeQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.query.impl.ValueRangeQuery;
import cn.edu.tsinghua.iotdb.benchmark.workload.schema.DeviceSchema;
import com.alibaba.fastjson.JSON;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.kairosdb.client.HttpClient;
import org.kairosdb.client.builder.Aggregator;
import org.kairosdb.client.builder.AggregatorFactory;
import org.kairosdb.client.builder.AggregatorFactory.FilterOperation;
import org.kairosdb.client.builder.QueryBuilder;
import org.kairosdb.client.builder.TimeUnit;
import org.kairosdb.client.builder.aggregator.SamplingAggregator;
import org.kairosdb.client.response.QueryResponse;
import org.kairosdb.client.response.QueryResult;
import org.kairosdb.client.response.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.kairosdb.client.builder.DataPoint;

public class KairosDB implements IDatabase {

  private static final Logger LOGGER = LoggerFactory.getLogger(KairosDB.class);
  private String writeUrl;
  private HttpClient client;
  private Config config;

  private static final String GROUP_STR = "group";
  private static final String DEVICE_STR = "device";

  public KairosDB() {
    config = ConfigDescriptor.getInstance().getConfig();
    writeUrl = config.DB_URL + "/api/v1/datapoints";

  }

  @Override
  public void init() throws TsdbException {
    try {
      client = new HttpClient(config.DB_URL);
    } catch (MalformedURLException e) {
      throw new TsdbException(
          "Init KairosDB client failed, the url is " + config.DB_URL + ". Message is " + e
              .getMessage());
    }
  }

  @Override
  public void cleanup() {
    try {
      for (String metric : client.getMetricNames()) {
        // skip kairosdb internal info metrics
        if(metric.contains("kairosdb.")){
          continue;
        }
        client.deleteMetric(metric);
      }
      // wait for deletion complete
      LOGGER.info("[KAIROSDB]:Waiting {}ms for old data deletion.", config.INIT_WAIT_TIME);
      Thread.sleep(config.INIT_WAIT_TIME);
    } catch (Exception e) {
      LOGGER.error("Delete old data failed because ", e);
    }
  }

  @Override
  public void close() throws TsdbException {
    try {
      client.close();
    } catch (IOException e) {
      throw new TsdbException("Close KairosDB client failed, because " + e.getMessage());
    }
  }

  @Override
  public void registerSchema(List<DeviceSchema> schemaList) {
    //no need for KairosDB
  }


  private LinkedList<KairosDataModel> createDataModel(DeviceSchema deviceSchema, long timestamp,
      List<String> recordValues) {
    LinkedList<KairosDataModel> models = new LinkedList<>();
    String groupId = deviceSchema.getGroup();
    int i = 0;
    for (String sensor : deviceSchema.getSensors()) {
      KairosDataModel model = new KairosDataModel();
      model.setName(sensor);
      // KairosDB do not support float as data type
      if (config.DATA_TYPE.equalsIgnoreCase("FLOAT")) {
        model.setType("double");
      } else {
        model.setType(config.DATA_TYPE.toLowerCase());
      }
      model.setTimestamp(timestamp);
      model.setValue(recordValues.get(i));
      Map<String, String> tags = new HashMap<>();
      tags.put(GROUP_STR, groupId);
      tags.put(DEVICE_STR, deviceSchema.getDevice());
      model.setTags(tags);
      models.addLast(model);
      i++;
    }
    return models;
  }

  @Override
  public Status insertOneBatch(Batch batch) {
    LinkedList<KairosDataModel> models = new LinkedList<>();
    for (Record record : batch.getRecords()) {
      models.addAll(createDataModel(batch.getDeviceSchema(), record.getTimestamp(),
          record.getRecordDataValue()));
    }
    String body = JSON.toJSONString(models);
    LOGGER.debug("body: {}", body);
    try {

      String response = HttpRequest.sendPost(writeUrl, body);

      LOGGER.debug("response: {}", response);
      return new Status(true);
    } catch (Exception e) {
      return new Status(false, 0, e, e.toString());
    }
  }

  @Override
  public Status preciseQuery(PreciseQuery preciseQuery) {
    long time = preciseQuery.getTimestamp();
    QueryBuilder builder = constructBuilder(time, time, preciseQuery.getDeviceSchema());
    return executeOneQuery(builder, false);
  }

  @Override
  public Status rangeQuery(RangeQuery rangeQuery) {
    long startTime;
    long endTime;
    if (config.IS_QUERYING){
      startTime = Constants.QUERY_START_TIMESTAMP;
      endTime = Constants.QUERY_END_TIMESTAMP;
    } else {
      startTime = rangeQuery.getStartTimestamp();
      endTime = rangeQuery.getEndTimestamp();
    }
    QueryBuilder builder = constructBuilder(startTime, endTime, rangeQuery.getDeviceSchema());
    return executeOneQuery(builder, false);
  }

  @Override
  public Status valueRangeQuery(ValueRangeQuery valueRangeQuery) {
    long startTime = valueRangeQuery.getStartTimestamp();
    long endTime = valueRangeQuery.getEndTimestamp();
    QueryBuilder builder = constructBuilder(startTime, endTime, valueRangeQuery.getDeviceSchema());
    Aggregator filterAggre = AggregatorFactory
        .createFilterAggregator(FilterOperation.LTE, valueRangeQuery.getValueThreshold());
    addAggreForQuery(builder, filterAggre);
    return executeOneQuery(builder, true);
  }

  @Override
  public Status aggRangeQuery(AggRangeQuery aggRangeQuery) {
    long startTime;
    long endTime;
    if (config.IS_QUERYING){
      startTime = Constants.QUERY_START_TIMESTAMP;
      endTime = Constants.QUERY_END_TIMESTAMP;
    } else {
      startTime = aggRangeQuery.getStartTimestamp();
      endTime = aggRangeQuery.getEndTimestamp();
    }
    QueryBuilder builder = constructBuilder(startTime, endTime, aggRangeQuery.getDeviceSchema());
    // convert to second
    int timeInterval = (int) (endTime - startTime) + 1;
    Aggregator aggregator = new SamplingAggregator(aggRangeQuery.getAggFun(), timeInterval,
        TimeUnit.MILLISECONDS);
    addAggreForQuery(builder, aggregator);
    return executeOneQuery(builder, true);
  }

  @Override
  public Status aggValueQuery(AggValueQuery aggValueQuery) {
    long startTime = aggValueQuery.getStartTimestamp();
    long endTime = aggValueQuery.getEndTimestamp();
    QueryBuilder builder = constructBuilder(startTime, endTime, aggValueQuery.getDeviceSchema());
    Aggregator funAggre = new SamplingAggregator(aggValueQuery.getAggFun(), 5000, TimeUnit.YEARS);
    Aggregator filterAggre = AggregatorFactory
        .createFilterAggregator(FilterOperation.LTE, aggValueQuery.getValueThreshold());
    addAggreForQuery(builder, filterAggre, funAggre);
    return executeOneQuery(builder, true);
  }

  @Override
  public Status aggRangeValueQuery(AggRangeValueQuery aggRangeValueQuery) {
    long startTime = aggRangeValueQuery.getStartTimestamp();
    long endTime = aggRangeValueQuery.getEndTimestamp();
    QueryBuilder builder = constructBuilder(startTime, endTime,
        aggRangeValueQuery.getDeviceSchema());
    int timeInterval = (int) (endTime - startTime) + 1;
    Aggregator funAggre = new SamplingAggregator(aggRangeValueQuery.getAggFun(), timeInterval,
        TimeUnit.SECONDS);
    Aggregator filterAggre = AggregatorFactory
        .createFilterAggregator(FilterOperation.LTE, aggRangeValueQuery.getValueThreshold());
    addAggreForQuery(builder, filterAggre, funAggre);
    return executeOneQuery(builder, true);
  }

  @Override
  public Status groupByQuery(GroupByQuery groupByQuery) {
    long startTime = groupByQuery.getStartTimestamp();
    long endTime = groupByQuery.getEndTimestamp();
    QueryBuilder builder = constructBuilder(startTime, endTime, groupByQuery.getDeviceSchema());
    Aggregator funAggre = new SamplingAggregator(groupByQuery.getAggFun(),
        (int) groupByQuery.getGranularity(), TimeUnit.MILLISECONDS);
    addAggreForQuery(builder, funAggre);
    return executeOneQuery(builder, true);
  }

  @Override
  public Status latestPointQuery(LatestPointQuery latestPointQuery) {
    //latestPointQuery
    if (config.IS_QUERYING == false) {
      long startTime = latestPointQuery.getStartTimestamp();
      long endTime = latestPointQuery.getEndTimestamp();
      QueryBuilder builder = constructBuilder(startTime, endTime, latestPointQuery.getDeviceSchema());
      Aggregator aggregator = AggregatorFactory.createLastAggregator(5000, TimeUnit.YEARS);
      addAggreForQuery(builder, aggregator);
      return executeOneQuery(builder, true);
    }
    else {
      long startTime = Constants.QUERY_START_TIMESTAMP;
      long endTime = Constants.QUERY_END_TIMESTAMP;
      QueryBuilder builder = constructBuilder(startTime, endTime, latestPointQuery.getDeviceSchema());
      return executeOneDelete(builder);
    }
  }

  private Status executeOneQuery(QueryBuilder builder, boolean isAggregate) {
    LOGGER.debug("[JSON] {}", builder);
    int queryResultPointNum = 0;
    try {
      QueryResponse response = client.query(builder);

      // confirm the result is the willing result
      for (QueryResult query : response.getQueries()) {
        for (Result result : query.getResults()) {
          int resSize = result.getDataPoints().size();
          queryResultPointNum += resSize;
          if (config.IS_QUERYING) {
            if (config.IS_DELETED) {
              if (result.getName().equals("s_0") && resSize != 0) {
                throw new Exception("Get the deleted sensor, the size is" + resSize);
              }
            }
            else if (!isAggregate) {
              if (Math.abs(expectedDataValue("count") - resSize) > 0.001) {
                throw new Exception("The data number is wrong in name:" + result.getName() +
                        " and tag:" + result.getTags() + ",the expected value is "
                        + Math.abs(expectedDataValue("count")) + ", real is "+ resSize);
              }
            }
          }
          for (int i = 0; i < resSize; i++) {
            DataPoint data = result.getDataPoints().get(i);
            if (config.IS_QUERYING) {
              if (isAggregate) {
                Double doubleValue = Double.valueOf(String.valueOf(data.getValue()));
                String aggFunc = config.QUERY_AGGREGATE_FUN.toLowerCase();
                Double expectResult = expectedDataValue(aggFunc);
                if (Math.abs(expectResult - doubleValue) > 0.001) {
                  throw new Exception("The query result wrong in " +
                          aggFunc + " agg func, the result value is "
                          + doubleValue + ", the expected value is " + expectResult
                          + ",resultName is " + result.getName() + ",resultTag is "
                          + result.getTags());
                }
              } else {
                if (data.getTimestamp() != data.longValue()) {
                  throw new Exception("Query result error in basic query");
                }
              }
            }
          }
        }
      }
      return new Status(true, queryResultPointNum);
    } catch (Exception e) {
      return new Status(false, 0, e, builder.toString());
    }
  }

  private Status executeOneDelete(QueryBuilder builder) {
    LOGGER.debug("[JSON] {}", builder);
    try {
      client.delete(builder);
      return new Status(true, 0);
    } catch (Exception e) {
      return new Status(false, 0, e, builder.toString());
    }
  }

  private QueryBuilder constructBuilder(long st, long et, List<DeviceSchema> deviceSchemaList) {
    QueryBuilder builder = QueryBuilder.getInstance();
    builder.setStart(new Date(st))
        .setEnd(new Date(et));
    for (DeviceSchema deviceSchema : deviceSchemaList) {
      for (String sensor : deviceSchema.getSensors()) {
        builder.addMetric(sensor)
            .addTag(DEVICE_STR, deviceSchema.getDevice())
            .addTag(GROUP_STR, deviceSchema.getGroup());
      }
    }
    return builder;
  }

  private void addAggreForQuery(QueryBuilder builder, Aggregator... aggregatorArray) {
    builder.getMetrics().forEach(queryMetric -> {
      for (Aggregator aggregator : aggregatorArray) {
        queryMetric.addAggregator(aggregator);
      }
    });
  }

  private double expectedDataValue(String aggFunc) {
      long startTime = Constants.QUERY_START_TIMESTAMP;
      long endTime = Constants.QUERY_END_TIMESTAMP;
      long insertEnd = Constants.INSERT_END_TIMESTAMP;
      long insertRestart = Constants.INSERT_RESTART_TIMESTAMP;
      long insert_offset = config.POINT_STEP;
      if (config.IS_DOUBLE_DATA) {
          switch (aggFunc) {
              case "count":
                  return (double)(insertEnd - startTime + endTime - insertRestart) / insert_offset + 2;
              case "max":
              case "last":
                  return endTime;
              case "min":
              case "first":
                  return startTime;
              case "avg":
                 // TODO Fix the calculate bug
                  return (double)(insertEnd + startTime + endTime + insertRestart) / 4;
              default:
                  return 0;
          }
      } else {
          switch (aggFunc) {
              case "count":
                  return (double)(endTime - startTime) / insert_offset + 1;
              case "max":
              case "last":
                  return endTime;
              case "min":
              case "first":
                  return startTime;
              case "avg":
                  return (double)(startTime + endTime) / 2;
              default:
                  return 0;
          }
      }
  }
}
