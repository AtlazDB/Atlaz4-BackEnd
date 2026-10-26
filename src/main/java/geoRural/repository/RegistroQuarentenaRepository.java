package geoRural.repository;

import geoRural.entity.RegistroQuarentena;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RegistroQuarentenaRepository extends JpaRepository<RegistroQuarentena, Long> {

    @Query("""
        SELECT q FROM RegistroQuarentena q 
        WHERE (:tipoErro IS NULL OR q.tipoErro = :tipoErro)
          AND (:status IS NULL OR q.statusAnalise = :status)
          AND (:texto IS NULL OR LOWER(q.chaveNatural) LIKE LOWER(CONCAT('%', :texto, '%')) 
               OR LOWER(q.valorRecebido) LIKE LOWER(CONCAT('%', :texto, '%')))
          AND (:fonteId IS NULL OR q.execucaoId IN (
               SELECT e.id FROM Execucao e WHERE e.arquivoBruto.fonte.id = :fonteId))
    """)
    Page<RegistroQuarentena> buscarComFiltros(
        @Param("tipoErro") String tipoErro,
        @Param("status") String status,
        @Param("texto") String texto,
        @Param("fonteId") Long fonteId,
        Pageable pageable
    );

    long countByStatusAnalise(String statusAnalise);
}