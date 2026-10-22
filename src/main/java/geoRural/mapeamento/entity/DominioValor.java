package geoRural.mapeamento.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.type.NumericBooleanConverter;

/** Valor permitido de um domínio (tabela DOMINIO_VALOR, V9). Somente leitura nesta tarefa. */
@Entity
@Table(name = "DOMINIO_VALOR")
public class DominioValor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "DOMINIO_ID", nullable = false)
    private Dominio dominio;

    @Column(name = "VALOR", nullable = false)
    private String valor;

    @Column(name = "DESCRICAO")
    private String descricao;

    @Column(name = "ORDEM", nullable = false)
    private Integer ordem = 0;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "ATIVO", nullable = false)
    private boolean ativo = true;

    public Long getId() { return id; }
    public Dominio getDominio() { return dominio; }
    public String getValor() { return valor; }
    public String getDescricao() { return descricao; }
    public Integer getOrdem() { return ordem; }
    public boolean isAtivo() { return ativo; }
}