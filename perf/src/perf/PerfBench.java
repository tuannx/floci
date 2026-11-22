package perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ssm.model.Parameter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Deterministic timing harness for Floci hot paths (no Quarkus boot).
 *
 * Fixed inputs, fixed warmup/measurement iteration counts, median of rounds.
 * Output: one line per benchmark: name, median ns/op, ops/sec, checksum.
 * The checksum is printed so the JIT cannot eliminate the measured work.
 */
public final class PerfBench {

    static final int WARMUP_ITERS = 20_000;
    static final int MEASURE_ROUNDS = 5;
    static final int MEASURE_ITERS = 50_000;

    // ---- Fixed inputs (deterministic) ----
    static final String QUEUE_URL = "http://localhost:4566/123456789012/orders-queue";
    static final String MESSAGE_BODY = "orderId=42 status=SHIPPED note=perf-fixed-input";
    static final String XML_REQUEST =
            "<Envelope xmlns=\"http://queue.amazonaws.com/doc/2012-11-05/\">"
            + "<LocationConstraint>us-east-1</LocationConstraint>"
            + "<Message><Body>" + MESSAGE_BODY + "</Body>"
            + "<Attribute><Name>SentTimestamp</Name><Value>1700000000000</Value></Attribute>"
            + "<Attribute><Name>ApproximateReceiveCount</Name><Value>3</Value></Attribute>"
            + "</Message></Envelope>";
    static final String ARN = "arn:aws:sqs:us-east-1:123456789012:orders-queue";

    interface Bench {
        long run(int iters);
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        Parameter param = new Parameter();
        param.setName("/app/prod/db/host");
        param.setValue("db.internal.example.com");
        param.setType("String");
        param.setVersion(7L);
        param.setLastModifiedDate(Instant.ofEpochSecond(1_700_000_000L));
        param.setDescription("perf fixed input parameter");
        param.setArn("arn:aws:ssm:us-east-1:123456789012:parameter/app/prod/db/host");
        param.setDataType("text");
        String paramJson = mapper.writeValueAsString(param);

        report("xml_builder_build", iters -> {
            long acc = 0;
            for (int i = 0; i < iters; i++) {
                String xml = new XmlBuilder()
                        .start("ReceiveMessageResponse", "http://queue.amazonaws.com/doc/2012-11-05/")
                        .start("ReceiveMessageResult")
                        .start("Message")
                        .elem("MessageId", "msg-00000042")
                        .elem("ReceiptHandle", "receipt-handle-fixed-42")
                        .elem("MD5OfBody", "0123456789abcdef0123456789abcdef")
                        .elem("Body", MESSAGE_BODY)
                        .end("Message")
                        .end("ReceiveMessageResult")
                        .raw("<ResponseMetadata><RequestId>req-fixed-42</RequestId></ResponseMetadata>")
                        .end("ReceiveMessageResponse")
                        .build();
                acc += xml.length();
            }
            return acc;
        });

        report("xml_parser_extract", iters -> {
            long acc = 0;
            for (int i = 0; i < iters; i++) {
                List<String> bodies = XmlParser.extractAll(XML_REQUEST, "Body");
                Map<String, String> attrs = XmlParser.extractPairs(XML_REQUEST, "Attribute", "Name", "Value");
                acc += bodies.size() + attrs.size();
            }
            return acc;
        });

        report("arn_parse", iters -> {
            long acc = 0;
            for (int i = 0; i < iters; i++) {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(ARN);
                String url = AwsArnUtils.arnToQueueUrl(ARN, "http://localhost:4566");
                acc += arn.resource().length() + url.length();
            }
            return acc;
        });

        report("jackson_ssm_parameter_roundtrip", iters -> {
            long acc = 0;
            for (int i = 0; i < iters; i++) {
                try {
                    String json = mapper.writeValueAsString(param);
                    Parameter back = mapper.readValue(json, Parameter.class);
                    acc += json.length() + back.getName().length();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return acc;
        });

        System.out.println("queue_url_len=" + QUEUE_URL.length() + " json_len=" + paramJson.length());
    }

    static void report(String name, Bench bench) {
        long warmChecksum = bench.run(WARMUP_ITERS);
        double[] nsPerOp = new double[MEASURE_ROUNDS];
        long checksum = warmChecksum;
        for (int r = 0; r < MEASURE_ROUNDS; r++) {
            long t0 = System.nanoTime();
            checksum += bench.run(MEASURE_ITERS);
            long elapsed = System.nanoTime() - t0;
            nsPerOp[r] = (double) elapsed / MEASURE_ITERS;
        }
        Arrays.sort(nsPerOp);
        double median = nsPerOp[MEASURE_ROUNDS / 2];
        System.out.printf("%s median_ns_per_op=%.1f ops_per_sec=%.0f checksum=%d%n",
                name, median, 1_000_000_000.0 / median, checksum);
    }
}
