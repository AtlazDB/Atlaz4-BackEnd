package geoRural.repository;

import geoRural.entity.ImovelRural;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImovelRuralRepository extends JpaRepository<ImovelRural, Long> {
    Optional<ImovelRural> findByCodImovel(String codImovel);
    List<ImovelRural> findByMunicipio(String municipio);
    List<ImovelRural> findByEstado(String estado);
    List<ImovelRural> findByMunicipioId(Long municipioId);
}