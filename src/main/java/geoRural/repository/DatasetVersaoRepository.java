package geoRural.repository;

import geoRural.entity.DatasetVersao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DatasetVersaoRepository extends JpaRepository<DatasetVersao, Long> {

    @Query("select coalesce(max(d.versao), 0) from DatasetVersao d where d.conjunto = :conjunto")
    Integer ultimaVersao(@Param("conjunto") String conjunto);

    /** Alguma execução deste arquivo gerou versão de dados? Se sim, há dado apontando para ele. */
    boolean existsByExecucao_ArquivoBruto_Id(Long arquivoBrutoId);
}
