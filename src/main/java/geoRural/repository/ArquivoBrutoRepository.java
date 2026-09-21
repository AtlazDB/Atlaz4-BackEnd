package geoRural.repository;

import geoRural.entity.ArquivoBruto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArquivoBrutoRepository extends JpaRepository<ArquivoBruto, Long> {
    List<ArquivoBruto> findByFonteIdOrderByRecebidoEmDesc(Long fonteId);
}
