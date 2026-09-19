package geoRural.config;

import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class ObjectStorageConfig {

    @Bean
    public ObjectStorage objectStorage() throws IOException {
        var provider = new ConfigFileAuthenticationDetailsProvider("DEFAULT");

        return ObjectStorageClient.builder()
                .build(provider);
    }
}
