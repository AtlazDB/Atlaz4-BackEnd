package geoRural.repository;

import geoRural.entity.ArquivoBruto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ArquivoBrutoRepository extends JpaRepository<ArquivoBruto, Long> {
    List<ArquivoBruto> findByFonteIdOrderByRecebidoEmDesc(Long fonteId);

    // Condicional no status: se alguém mandou reprocessar no meio da exclusão, não apaga nada.
    @Modifying
    @Query(value = "DELETE FROM arquivo_bruto WHERE id = :id AND status = 'REJEITADO'", nativeQuery = true)
    int apagarSeRejeitado(@Param("id") Long id);
}
