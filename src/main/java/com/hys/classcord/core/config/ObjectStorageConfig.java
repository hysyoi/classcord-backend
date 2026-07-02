package com.hys.classcord.core.config;

import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@RequiredArgsConstructor
public class ObjectStorageConfig {

    private final ObjectStorageProperties properties;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        properties.getAccessKey(), properties.getSecretKey())))
                .region(getRegionFromEndpoint(properties.getEndpoint()))
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        properties.getAccessKey(), properties.getSecretKey())))
                .region(getRegionFromEndpoint(properties.getEndpoint()))
                .build();
    }

    private Region getRegionFromEndpoint(String endpoint) {
        if (endpoint == null) {
            return Region.US_EAST_1;
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host == null) {
                host = endpoint;
            }
            // 針對 Backblaze B2 S3 API 端點進行解析，格式例如：s3.us-west-004.backblazeb2.com
            if (host.startsWith("s3.") && host.contains(".backblazeb2.com")) {
                String regionStr = host.substring(3, host.indexOf(".backblazeb2.com"));
                return Region.of(regionStr);
            }
        } catch (Exception e) {
            // 忽略例外，使用預設值
        }
        return Region.US_EAST_1;
    }
}
