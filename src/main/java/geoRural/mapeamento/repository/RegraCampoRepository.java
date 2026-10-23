package geoRural.mapeamento.repository;

import geoRural.mapeamento.entity.RegraCampo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RegraCampoRepository extends JpaRepository<RegraCampo, Long> {

    /** Regras da fonte, já com a coluna de origem e o domínio carregados, na ordem das colunas. */
    @Query("""
            select r from RegraCampo r
            join fetch r.fonteColuna c
            left join fetch r.dominio
            where r.fonte.id = :fonteId
            order by c.ordem, r.campoDestino
            """)
    List<RegraCampo> listarPorFonte(@Param("fonteId") Long fonteId);

    /** A regra só é devolvida se pertencer à fonte informada. */
    @Query("""
            select r from RegraCampo r
            where r.id = :id and r.fonte.id = :fonteId
            """)
    Optional<RegraCampo> buscarDaFonte(@Param("id") Long id, @Param("fonteId") Long fonteId);

    @Query("select count(r) > 0 from RegraCampo r where r.fonteColuna.id = :colunaId")
    boolean existePorColuna(@Param("colunaId") Long colunaId);

    @Query("""
            select count(r) > 0 from RegraCampo r
            where r.fonte.id = :fonteId
              and r.destinoTabela = :destinoTabela
              and r.campoDestino = :campoDestino
            """)
    boolean existePorDestino(@Param("fonteId") Long fonteId,
                             @Param("destinoTabela") String destinoTabela,
                             @Param("campoDestino") String campoDestino);
}