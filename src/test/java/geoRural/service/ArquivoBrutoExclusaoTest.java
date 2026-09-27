package geoRural.service;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.ExclusaoNaoPermitidaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contra o Oracle do docker-compose e SEM @Transactional, como o CargaMunicipioServiceTest: a
 * exclusão abre a própria transação, e o que interessa é o que ficou commitado. Limpeza manual.
 */
@SpringBootTest
class ArquivoBrutoExclusaoTest {

    /** O cliente da OCI: evita exigir ~/.oci/config e permite conferir o deleteObject. */
    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private ArquivoBrutoService arquivoBrutoService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> arquivosCriados = new ArrayList<>();

    @AfterEach
    void limpar() {
        for (Long id : arquivosCriados) {
            jdbc.update("DELETE FROM registro_quarentena WHERE execucao_id IN "
                    + "(SELECT id FROM execucao WHERE arquivo_bruto_id = ?)", id);
            jdbc.update("DELETE FROM dataset_versao WHERE execucao_id IN "
                    + "(SELECT id FROM execucao WHERE arquivo_bruto_id = ?)", id);
            jdbc.update("DELETE FROM execucao WHERE arquivo_bruto_id = ?", id);
            jdbc.update("DELETE FROM arquivo_bruto WHERE id = ?", id);
        }
    }

    @Test
    void apagaRejeitadoDoBancoEDoBucket() {
        long arquivo = criarArquivo("REJEITADO");
        long execucao = criarExecucao(arquivo, "FALHOU");
        jdbc.update("INSERT INTO registro_quarentena (execucao_id, numero_linha, motivo) VALUES (?, 1, 'teste')",
                execucao);
        String chave = chaveDe(arquivo);

        arquivoBrutoService.excluirRejeitado(arquivo);

        assertThat(contar("SELECT COUNT(*) FROM arquivo_bruto WHERE id = ?", arquivo)).isZero();
        assertThat(contar("SELECT COUNT(*) FROM execucao WHERE arquivo_bruto_id = ?", arquivo)).isZero();
        assertThat(contar("SELECT COUNT(*) FROM registro_quarentena WHERE execucao_id = ?", execucao)).isZero();

        ArgumentCaptor<DeleteObjectRequest> pedido = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(objectStorage).deleteObject(pedido.capture());
        assertThat(pedido.getValue().getObjectName()).isEqualTo(chave);
    }

    @Test
    void naoApagaArquivoQueNaoEstaRejeitado() {
        long arquivo = criarArquivo("PROCESSADO");

        assertThatThrownBy(() -> arquivoBrutoService.excluirRejeitado(arquivo))
                .isInstanceOf(ExclusaoNaoPermitidaException.class);

        assertThat(contar("SELECT COUNT(*) FROM arquivo_bruto WHERE id = ?", arquivo)).isEqualTo(1);
        verify(objectStorage, never()).deleteObject(any());
    }

    @Test
    void naoApagaRejeitadoQueJaGerouVersao() {
        // Processado uma vez (gerou versão), depois falhou num reprocessamento e ficou REJEITADO.
        long arquivo = criarArquivo("REJEITADO");
        long concluida = criarExecucao(arquivo, "CONCLUIDA");
        criarExecucao(arquivo, "FALHOU");
        jdbc.update("""
                INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona)
                VALUES ('teste_exclusao', 1, ?, 'TRATADA')
                """, concluida);

        assertThatThrownBy(() -> arquivoBrutoService.excluirRejeitado(arquivo))
                .isInstanceOf(ExclusaoNaoPermitidaException.class)
                .hasMessageContaining("versão");

        assertThat(contar("SELECT COUNT(*) FROM execucao WHERE arquivo_bruto_id = ?", arquivo)).isEqualTo(2);
        verify(objectStorage, never()).deleteObject(any());
    }

    @Test
    void falhaNoBucketNaoDesfazOBanco() {
        long arquivo = criarArquivo("REJEITADO");
        when(objectStorage.deleteObject(any())).thenThrow(new RuntimeException("bucket fora do ar"));

        arquivoBrutoService.excluirRejeitado(arquivo);

        assertThat(contar("SELECT COUNT(*) FROM arquivo_bruto WHERE id = ?", arquivo)).isZero();
    }

    @Test
    void arquivoInexistenteResponde404() {
        assertThatThrownBy(() -> arquivoBrutoService.excluirRejeitado(-1L))
                .isInstanceOf(ArquivoNaoEncontradoException.class);
    }

    private long criarArquivo(String status) {
        Long fonte = jdbc.queryForObject("SELECT id FROM fonte WHERE sigla = 'CAR'", Long.class);
        String chave = "bruta/CAR/teste/" + UUID.randomUUID() + "_exclusao.zip";
        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256, status)
                VALUES (?, ?, 'exclusao.zip', 1, 'x', ?)
                """, fonte, chave, status);
        Long id = jdbc.queryForObject("SELECT id FROM arquivo_bruto WHERE chave_objeto = ?", Long.class, chave);
        arquivosCriados.add(id);
        return id;
    }

    private long criarExecucao(long arquivo, String status) {
        jdbc.update("INSERT INTO execucao (arquivo_bruto_id, tipo, status) VALUES (?, 'VALIDACAO', ?)",
                arquivo, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM execucao WHERE arquivo_bruto_id = ?", Long.class, arquivo);
    }

    private String chaveDe(long arquivo) {
        return jdbc.queryForObject("SELECT chave_objeto FROM arquivo_bruto WHERE id = ?", String.class, arquivo);
    }

    private long contar(String sql, Object... parametros) {
        return jdbc.queryForObject(sql, Long.class, parametros);
    }
}
