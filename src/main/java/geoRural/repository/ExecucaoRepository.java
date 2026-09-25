package geoRural.repository;

import geoRural.entity.Execucao;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecucaoRepository extends JpaRepository<Execucao, Long> {
}
