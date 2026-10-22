package geoRural.mapeamento.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import geoRural.mapeamento.entity.Dominio;

import java.util.List;

public interface DominioRepository extends JpaRepository<Dominio, Long> {

    /** Domínios (ativos ou não, conforme o parâmetro) já com os valores, ordenados por código. */
    @Query("""
            select distinct d from Dominio d
            left join fetch d.valores
            where d.ativo = :ativo
            order by d.codigo
            """)
    List<Dominio> listarComValores(@Param("ativo") boolean ativo);
}