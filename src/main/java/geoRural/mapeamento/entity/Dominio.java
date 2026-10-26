package geoRural.mapeamento.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Lista nomeada de valores permitidos (tabela DOMINIO, V9). Somente leitura nesta tarefa. */
@Entity
@Table(name = "DOMINIO")
public class Dominio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CODIGO", nullable = false, unique = true)
    private String codigo;

    @Column(name = "NOME", nullable = false)
    private String nome;

    @Column(name = "DESCRICAO")
    private String descricao;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "ATIVO", nullable = false)
    private boolean ativo = true;

    @Column(name = "CRIADO_EM", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @OneToMany(mappedBy = "dominio")
    @OrderBy("ordem ASC, valor ASC")
    private List<DominioValor> valores = new ArrayList<>();

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNome() { return nome; }
    public String getDescricao() { return descricao; }
    public boolean isAtivo() { return ativo; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }
    public List<DominioValor> getValores() { return valores; }
}