package geoRural.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "FONTE")
public class Fonte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "NOME", nullable = false)
    private String nome;

    @Column(name = "SIGLA", nullable = false, unique = true)
    private String sigla;

    @Column(name = "ORGAO", nullable = false)
    private String orgao;

    @Column(name = "FORMATO", nullable = false)
    private String formato;

    @Column(name = "PERIODICIDADE", nullable = false)
    private String periodicidade;

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "ATIVA", nullable = false)
    private boolean ativa = true;

    @CreationTimestamp
    @Column(name = "CRIADA_EM", nullable = false, updatable = false)
    private OffsetDateTime criadaEm;

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public String getSigla() { return sigla; }
    public void setSigla(String sigla) { this.sigla = sigla; }

    public String getOrgao() { return orgao; }
    public void setOrgao(String orgao) { this.orgao = orgao; }

    public String getFormato() { return formato; }
    public void setFormato(String formato) { this.formato = formato; }

    public String getPeriodicidade() { return periodicidade; }
    public void setPeriodicidade(String periodicidade) { this.periodicidade = periodicidade; }

    public boolean isAtiva() { return ativa; }
    public void setAtiva(boolean ativa) { this.ativa = ativa; }

    public OffsetDateTime getCriadaEm() { return criadaEm; }
}
