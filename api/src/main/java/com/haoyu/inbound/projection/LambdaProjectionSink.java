package com.haoyu.inbound.projection;

import com.haoyu.inbound.common.AppProperties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Invokes the projector Lambda asynchronously (InvocationType=Event): the box only needs
 * lambda:InvokeFunction on that one function, and a slow or failing Lambda never blocks the
 * Kafka consumer. Credentials come from the default provider chain (env vars in compose).
 */
@Component
@ConditionalOnProperty(prefix = "app.aws", name = "projector-function")
class LambdaProjectionSink implements ProjectionSink {

    private static final Logger log = LoggerFactory.getLogger(LambdaProjectionSink.class);

    private final LambdaClient lambda;
    private final String functionName;
    private final ObjectMapper json;

    LambdaProjectionSink(AppProperties props, ObjectMapper json) {
        this.lambda = LambdaClient.builder().region(Region.of(props.aws().region())).build();
        this.functionName = props.aws().projectorFunction();
        this.json = json;
    }

    @Override
    public void push(String source, List<AvailabilityItem> items) {
        if (items.isEmpty()) return;
        String payload = json.writeValueAsString(Map.of("source", source, "items", items));
        var response = lambda.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String(payload))
                .build());
        log.info("projection {}: {} items queued to {} (status {})", source, items.size(), functionName, response.statusCode());
    }
}
