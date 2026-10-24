package geoRural.service;

import geoRural.exception.RequisicaoInvalidaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sem rede: só endereços literais, que não precisam de DNS. */
class ValidadorUrlExternaTest {

    private final ValidadorUrlExterna validador = new ValidadorUrlExterna();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/dados.csv",
            "http://localhost:8080/api/v1/fontes",
            "http://169.254.169.254/opc/v2/instance/",   // metadados da VM na OCI
            "http://10.0.0.5:1521/",
            "http://192.168.0.10/",
            "http://172.16.3.4/",
            "http://100.64.0.1/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[fd00::1]/",
    })
    void recusaEnderecoInterno(String url) {
        assertThatThrownBy(() -> validador.validar(url))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("endereço interno");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://8.8.8.8/x.zip", "file:///etc/passwd", "8.8.8.8/x", "gopher://8.8.8.8/"})
    void soAceitaHttpEHttps(String url) {
        assertThatThrownBy(() -> validador.validar(url))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("http ou https");
    }

    @Test
    void aceitaEnderecoPublico() {
        assertThat(validador.validar("https://8.8.8.8/dados.zip").getHost()).isEqualTo("8.8.8.8");
    }

    @Test
    void urlMalformadaERecusada() {
        assertThatThrownBy(() -> validador.validar("http://exemplo .com/ x"))
                .isInstanceOf(RequisicaoInvalidaException.class);
    }
}
