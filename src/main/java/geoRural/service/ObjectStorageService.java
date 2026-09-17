package geoRural.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;

@Service
public class ObjectStorageService {

    private final ObjectStorage objectStorage;

    @Value("${oci.namespace}")
    private String namespace;

    @Value("${oci.bucket}")
    private String bucket;

    public ObjectStorageService(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    public void enviarArquivo(Path arquivo) throws IOException {

        String nomeArquivo = arquivo.getFileName().toString();

        try (InputStream inputStream = Files.newInputStream(arquivo)) {

            PutObjectRequest request = PutObjectRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .objectName(nomeArquivo)
                    .putObjectBody(inputStream)
                    .contentLength(Files.size(arquivo))
                    .build();

            objectStorage.putObject(request);
        }
    }
}