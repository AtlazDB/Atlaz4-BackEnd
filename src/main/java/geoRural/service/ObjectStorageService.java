package geoRural.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
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

    public Path baixar(String chave) throws IOException {
        var resposta = objectStorage.getObject(GetObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName(chave).build());
        Path destino = Files.createTempFile("bruto-", ".zip");
        try (InputStream in = resposta.getInputStream()) {
            Files.copy(in, destino, StandardCopyOption.REPLACE_EXISTING);
        }
        return destino;
    }

    /**
     * Apaga um objeto do bucket. Na zona bruta, só para arquivo rejeitado que nunca gerou versão
     * de dados (ver ArquivoBrutoServiceImpl.excluirRejeitado) — o resto da zona bruta é imutável.
     */
    public void apagar(String chave) {
        objectStorage.deleteObject(DeleteObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName(chave).build());
    }
}
