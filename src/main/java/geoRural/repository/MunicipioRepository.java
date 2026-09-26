package geoRural.repository;

import geoRural.entity.Municipio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MunicipioRepository extends JpaRepository<Municipio, Long> {

    Optional<Municipio> findByCodIbge(String codIbge);

    List<Municipio> findByEstadoOrderByNome(String estado);

    List<Municipio> findAllByOrderByEstadoAscNomeAsc();

    /**
     * Municípios cuja geometria toca o ponto informado. É a consulta que a etapa
     * CRUZAMENTO vai usar para descobrir o município de cada imóvel.
     *
     * SDO_RELATE com máscara ANYINTERACT é "qualquer interação a não ser
     * disjunto", então um ponto na divisa devolve os dois municípios vizinhos —
     * por isso o retorno é lista, e não Optional.
     *
     * A consulta é nativa porque SDO_RELATE é função do Oracle Spatial, sem
     * equivalente em JPQL, e usa o índice espacial ix_municipio_geometria.
     */
    @Query(value = """
            SELECT m.*
              FROM municipio m
             WHERE SDO_RELATE(
                       m.geometria,
                       SDO_GEOMETRY(2001, 4326,
                                    SDO_POINT_TYPE(:longitude, :latitude, NULL),
                                    NULL, NULL),
                       'mask=ANYINTERACT') = 'TRUE'
            """, nativeQuery = true)
    List<Municipio> buscarPorPonto(@Param("longitude") double longitude,
                                   @Param("latitude") double latitude);
}
