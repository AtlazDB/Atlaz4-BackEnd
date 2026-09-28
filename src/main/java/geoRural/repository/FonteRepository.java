package geoRural.repository;

import geoRural.entity.Fonte;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FonteRepository extends JpaRepository<Fonte, Long> {

    boolean existsBySiglaIgnoreCase(String sigla);

    List<Fonte> findAllByOrderByCriadaEmDesc();

    @Query("""
            select f from Fonte f
            where lower(f.nome)  like lower(concat('%', :busca, '%'))
               or lower(f.sigla) like lower(concat('%', :busca, '%'))
               or lower(f.orgao) like lower(concat('%', :busca, '%'))
            order by f.criadaEm desc
            """)
    List<Fonte> buscar(@Param("busca") String busca);
}
