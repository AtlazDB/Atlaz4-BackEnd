package geoRural.mapeamento.repository;

import geoRural.mapeamento.entity.Dominio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DominioRepository extends JpaRepository<Dominio, Long> {

    /** Domínios (ativos ou não, conforme o parâmetro) já com os valores, ordenados por código. */
    @Query("""
            select distinct d from Dominio d
            left join fetch d.valores
            where d.ativo = :ativo
            order by d.codigo
            """)
    List<Dominio> listarComValores(@Param("ativo") boolean ativo);

    /** Domínio pelo id, só se estiver ativo. */
    @Query("""
            select d from Dominio d
            where d.id = :id and d.ativo = :ativo
            """)
    Optional<Dominio> buscarPorIdEAtivo(@Param("id") Long id, @Param("ativo") boolean ativo);
}