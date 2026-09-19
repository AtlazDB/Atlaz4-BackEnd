package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;

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

    public void enviar(String chave, InputStream conteudo, long tamanhoBytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(chave)
                .contentType(contentType)
                .contentLength(tamanhoBytes)
                .putObjectBody(conteudo)
                .build();

        objectStorage.putObject(request);
    }
}
