package cn.shangjingu.platform.api.platform;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.document.FileDownloadGuard;
import cn.shangjingu.platform.document.FileObjectService;
import cn.shangjingu.platform.document.FileObjectStorage;
import cn.shangjingu.platform.document.MinioFileObjectStorage;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.stepup.StepUpService;
import io.minio.MinioClient;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="platform.file",name="enabled",havingValue="true")
public class PlatformDocumentRuntimeConfiguration {
    @Bean @ConditionalOnMissingBean
    FileDownloadAuthorizationTargetResolver fileDownloadAuthorizationTargetResolver() { return file -> Optional.empty(); }

    @Bean @ConditionalOnMissingBean
    FileDownloadGuard platformFileDownloadGuard(AuthorizationService authorization, StepUpService stepUp,
                                                 JdbcSecurityAuditService audit, FileDownloadAuthorizationTargetResolver targets) {
        return new PlatformFileDownloadGuard(authorization, stepUp, audit, targets);
    }

    @Bean @ConditionalOnMissingBean
    MinioClient platformMinioClient(@Value("${platform.file.minio.endpoint}") String endpoint,
                                    @Value("${platform.file.minio.access-key}") String accessKey,
                                    @Value("${platform.file.minio.secret-key}") String secretKey) {
        if(endpoint==null||endpoint.isBlank()||accessKey==null||accessKey.isBlank()||secretKey==null||secretKey.isBlank()) throw new IllegalStateException("platform.file MinIO credentials must be explicitly configured");
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey,secretKey).build();
    }

    @Bean @ConditionalOnMissingBean
    FileObjectStorage platformFileObjectStorage(MinioClient client) { return new MinioFileObjectStorage(client); }

    @Bean @ConditionalOnMissingBean
    FileObjectService platformFileObjectService(JdbcTemplate jdbc, FileObjectStorage storage, FileDownloadGuard guard) {
        return new FileObjectService(jdbc, storage, guard);
    }
}
