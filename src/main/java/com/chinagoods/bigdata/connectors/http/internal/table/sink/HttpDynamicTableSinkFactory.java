package com.chinagoods.bigdata.connectors.http.internal.table.sink;

import java.util.Properties;
import java.util.Set;

import com.chinagoods.bigdata.connectors.http.internal.config.SinkRequestEncryptionMode;
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.base.table.AsyncDynamicTableSinkFactory;
import org.apache.flink.connector.base.table.sink.options.AsyncSinkConfigurationValidator;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.factories.FactoryUtil;

import com.chinagoods.bigdata.connectors.http.HttpPostRequestCallbackFactory;
import com.chinagoods.bigdata.connectors.http.internal.config.HttpConnectorConfigProperties;
import com.chinagoods.bigdata.connectors.http.internal.sink.httpclient.HttpRequest;
import com.chinagoods.bigdata.connectors.http.internal.utils.ConfigUtils;

import static com.chinagoods.bigdata.connectors.http.internal.table.sink.HttpDynamicSinkConnectorOptions.*;

/**
 * Factory for creating {@link HttpDynamicSink}.
 */
public class HttpDynamicTableSinkFactory extends AsyncDynamicTableSinkFactory {

    public static final String IDENTIFIER = "http-sink";

    @Override
    public DynamicTableSink createDynamicTableSink(Context context) {
        final AsyncDynamicSinkContext factoryContext = new AsyncDynamicSinkContext(this, context);

        // This is actually same as calling helper.getOptions();
        ReadableConfig tableOptions = factoryContext.getTableOptions();

        // Validate configuration
        FactoryUtil.createTableFactoryHelper(this, context)
                .validateExcept(
                        // properties coming from org.apache.flink.table.api.config.ExecutionConfigOptions
                        "table.",
                        HttpConnectorConfigProperties.GID_CONNECTOR_HTTP
                );
        validateHttpSinkOptions(tableOptions);

        Properties asyncSinkProperties =
                new AsyncSinkConfigurationValidator(tableOptions).getValidatedConfigurations();

        // generics type erasure, so we have to do an unchecked cast
        final HttpPostRequestCallbackFactory<HttpRequest> postRequestCallbackFactory =
                FactoryUtil.discoverFactory(
                        context.getClassLoader(),
                        HttpPostRequestCallbackFactory.class,  // generics type erasure
                        tableOptions.get(REQUEST_CALLBACK_IDENTIFIER)
                );

        Properties httpConnectorProperties =
                ConfigUtils.getHttpConnectorProperties(context.getCatalogTable().getOptions());

        HttpDynamicSink.HttpDynamicTableSinkBuilder builder =
                new HttpDynamicSink.HttpDynamicTableSinkBuilder()
                        .setTableOptions(tableOptions)
                        .setEncodingFormat(factoryContext.getEncodingFormat())
                        .setHttpPostRequestCallback(
                                postRequestCallbackFactory.createHttpPostRequestCallback()
                        )
                        .setConsumedDataType(factoryContext.getPhysicalDataType())
                        .setProperties(httpConnectorProperties);
        addAsyncOptionsToBuilder(asyncSinkProperties, builder);

        return builder.build();
    }


    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        return SetUtils.unmodifiableSet(URL, FactoryUtil.FORMAT);
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        Set<ConfigOption<?>> options = super.optionalOptions();
        options.add(INSERT_METHOD);
        options.add(REQUEST_CALLBACK_IDENTIFIER);
        return options;
    }

    private void validateHttpSinkOptions(ReadableConfig tableOptions)
            throws IllegalArgumentException {
        tableOptions.getOptional(INSERT_METHOD).ifPresent(insertMethod -> {
            if (!SetUtils.unmodifiableSet("POST", "PUT").contains(insertMethod)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Invalid option '%s'. It is expected to be either 'POST' or 'PUT'.",
                                INSERT_METHOD.key()
                        ));
            }
        });

        // 校验加密方式，目前支持2种方式
        tableOptions.getOptional(REQUEST_ENCRYPTION_MODE).ifPresent(mode -> {
            SinkRequestEncryptionMode encryptionMode = SinkRequestEncryptionMode.PLAIN;
            try {
                encryptionMode = SinkRequestEncryptionMode.valueOf(StringUtils.upperCase(mode));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        String.format(
                                "Invalid option '%s', value: '%s'. It is expected to be either 'plain' or 'xsyk'.",
                                REQUEST_ENCRYPTION_MODE.key(), mode
                        ));
            }

            // 若枚举值为xsyk，则加密公钥必填
            if (SinkRequestEncryptionMode.XSYK.getMode().equals(mode)) {
                String pubKey = tableOptions.getOptional(REQUEST_ENCRYPTION_XSYK_PUB_KEY).orElse("");
                if (StringUtils.isBlank(pubKey)) {
                    throw new IllegalArgumentException(
                            String.format("If the encryption method is xsyk, current value is [%s], the public key is required.", pubKey));
                }
                String appId = tableOptions.getOptional(REQUEST_ENCRYPTION_XSYK_APP_ID).orElse("");
                if (StringUtils.isBlank(appId)) {
                    throw new IllegalArgumentException(
                            String.format("If the encryption method is xsyk, current value is [%s], the app id is required.", appId));
                }
            }
        });
    }
}
