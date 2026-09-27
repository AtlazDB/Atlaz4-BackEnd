package geoRural.repository;

import geoRural.entity.Execucao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExecucaoRepository extends JpaRepository<Execucao, Long> {

    // registro_quarentena não tem entidade: a exclusão vai em SQL nativo.
    @Modifying
    @Query(value = """
            DELETE FROM registro_quarentena
             WHERE execucao_id IN (SELECT id FROM execucao WHERE arquivo_bruto_id = :arquivoId)
            """, nativeQuery = true)
    int apagarQuarentenaDoArquivo(@Param("arquivoId") Long arquivoId);

    @Modifying
    @Query(value = "DELETE FROM execucao WHERE arquivo_bruto_id = :arquivoId", nativeQuery = true)
    int apagarDoArquivo(@Param("arquivoId") Long arquivoId);
}
