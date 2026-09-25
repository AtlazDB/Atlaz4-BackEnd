package geoRural.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "DATASET_VERSAO")
public class DatasetVersao {

    public static final String CONJUNTO_IMOVEIS_RURAIS = "imoveis_rurais";

    public static final String ZONA_TRATADA = "TRATADA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CONJUNTO", nullable = false)
    private String conjunto;

    @Column(name = "VERSAO", nullable = false)
    private Integer versao;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "EXECUCAO_ID", nullable = false)
    private Execucao execucao;

    @Column(name = "CHAVE_OBJETO", nullable = false)
    private String chaveObjeto;

    @Column(name = "ZONA", nullable = false)
    private String zona;

    @Column(name = "REGISTROS")
    private Integer registros;

    @Column(name = "PUBLICADA_EM")
    private OffsetDateTime publicadaEm;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConjunto() { return conjunto; }
    public void setConjunto(String conjunto) { this.conjunto = conjunto; }

    public Integer getVersao() { return versao; }
    public void setVersao(Integer versao) { this.versao = versao; }

    public Execucao getExecucao() { return execucao; }
    public void setExecucao(Execucao execucao) { this.execucao = execucao; }

    public String getChaveObjeto() { return chaveObjeto; }
    public void setChaveObjeto(String chaveObjeto) { this.chaveObjeto = chaveObjeto; }

    public String getZona() { return zona; }
    public void setZona(String zona) { this.zona = zona; }

    public Integer getRegistros() { return registros; }
    public void setRegistros(Integer registros) { this.registros = registros; }

    public OffsetDateTime getPublicadaEm() { return publicadaEm; }
    public void setPublicadaEm(OffsetDateTime publicadaEm) { this.publicadaEm = publicadaEm; }
}
