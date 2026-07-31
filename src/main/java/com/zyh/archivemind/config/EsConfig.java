package com.zyh.archivemind.config;

import co.elastic.clients.transport.ElasticsearchTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import javax.net.ssl.SSLContext;
import java.security.cert.X509Certificate;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;


// Elasticsearch客户端配置类
@Configuration
public class EsConfig {

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.port}")
    private int port;

    @Value("${elasticsearch.scheme:https}")
    private String scheme;

    @Value("${elasticsearch.username:elastic}")
    private String username;

    @Value("${elasticsearch.password:changeme}")
    private String password;

    @Value("${elasticsearch.insecure-skip-tls:false}")
    private boolean skipTls;

    private static final Logger logger = LoggerFactory.getLogger(EsConfig.class);

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 创建低级客户端
        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, scheme));

        // 设置基本认证
        if (username != null && !username.isEmpty()) {
            BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                // TLS 证书跳过仅限开发/测试环境
                if (skipTls) {
                    logger.warn("!!! ES TLS 证书校验已禁用，仅限开发/测试环境 !!!");
                    try {
                        SSLContext sslContext = SSLContexts.custom()
                                .loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true)
                                .build();
                        httpClientBuilder.setSSLContext(sslContext);
                        httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                    } catch (Exception e) {
                        throw new RuntimeException("TLS 配置失败", e);
                    }
                }
                return httpClientBuilder.setDefaultCredentialsProvider(credsProvider);
            });
        }

        RestClient restClient = builder.build();

        // 创建传输层
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper()
        );

        // 返回高级客户端
        return new ElasticsearchClient(transport);
    }
}
