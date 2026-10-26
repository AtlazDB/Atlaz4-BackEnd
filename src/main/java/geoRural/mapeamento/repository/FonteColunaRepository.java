package geoRural.mapeamento.repository;

import geoRural.mapeamento.entity.FonteColuna;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FonteColunaRepository extends JpaRepository<FonteColuna, Long> {

    /**
     * Cada linha é um par [FonteColuna, RegraCampo]; a regra é null quando a coluna não está mapeada.
     * A situação (mapeada, nova, ausente) é decidida em SituacaoColuna, a partir deste par.
     */
    @Query("""
            select c, r
              from FonteColuna c
              left join RegraCampo r on r.fonteColuna = c
             where c.fonte.id = :fonteId
             order by c.ordem, c.nomeColuna
            """)
    List<Object[]> listarComRegra(@Param("fonteId") Long fonteId);

    /** A coluna só é devolvida se pertencer à fonte informada. */
    @Query("""
            select c from FonteColuna c
            where c.id = :id and c.fonte.id = :fonteId
            """)
    Optional<FonteColuna> buscarDaFonte(@Param("id") Long id, @Param("fonteId") Long fonteId);
}