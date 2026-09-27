package geoRural.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import geoRural.dto.ProcessamentoResponse;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.ProcessamentoEmAndamentoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.service.GravadorImoveis.ArquivoParaProcessar;
import geoRural.service.GravadorImoveis.VersaoCriada;
import geoRural.service.ValidadorImovel.Resultado;

@Service
public class ProcessamentoCarServiceImpl implements ProcessamentoCarService {

    private static final Logger log = LoggerFactory.getLogger(ProcessamentoCarServiceImpl.class);

    static final int TAMANHO_LOTE = 1000;

    private final GravadorImoveis gravador;
    private final LeitorShapefile leitor;
    private final ObjectStorageService objectStorage;
    private final TransactionTemplate transacao;

    public ProcessamentoCarServiceImpl(GravadorImoveis gravador, LeitorShapefile leitor,
                                       ObjectStorageService objectStorage, TransactionTemplate transacao) {
        this.gravador = gravador;
        this.leitor = leitor;
        this.objectStorage = objectStorage;
        this.transacao = transacao;
    }

    @Override
    public ProcessamentoResponse processar(Long arquivoId) {
        ArquivoParaProcessar arquivo = gravador.buscarArquivo(arquivoId)
                .orElseThrow(() -> new ArquivoNaoEncontradoException(arquivoId));

        if (!"CAR".equalsIgnoreCase(arquivo.siglaFonte())
                || !arquivo.nomeOriginal().toLowerCase().endsWith(".zip")) {
            throw new RequisicaoInvalidaException(
                    "Só arquivos .zip da fonte CAR (shapefile de imóveis) podem ser processados.");
        }
        if (!gravador.marcarProcessando(arquivoId)) {
            throw new ProcessamentoEmAndamentoException(arquivoId);
        }

        // PROCESSANDO e a execução ficam gravados fora da transação principal: se o resto falhar,
        // eles continuam no banco para receber FALHOU / REJEITADO.
        Long execucaoId = null;
        Path zip = null;
        try {
            execucaoId = gravador.iniciarExecucao(arquivoId);
            zip = objectStorage.baixar(arquivo.chaveObjeto());

            long execucao = execucaoId;
            Path zipBaixado = zip;
            // Uma transação só para versão + imóveis + quarentena + conclusão: se der erro no meio,
            // nada disso fica pela metade (nem imóvel apontando para uma versão que falhou).
            ProcessamentoResponse resposta = transacao.execute(status -> gravar(arquivoId, execucao, zipBaixado));
            log.info("Arquivo {} processado: {}", arquivoId, resposta);
            return resposta;
        } catch (Exception e) {
            log.error("Falha ao processar o arquivo {}", arquivoId, e);
            String mensagem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            gravador.falhar(arquivoId, execucaoId, mensagem);
            return ProcessamentoResponse.rejeitado(arquivoId, execucaoId, mensagem);
        } finally {
            apagar(zip);
        }
    }

    private ProcessamentoResponse gravar(long arquivoId, long execucaoId, Path zip) {
        VersaoCriada versao = gravador.criarVersao(execucaoId);
        ValidadorImovel validador = new ValidadorImovel();
        Contagem contagem = new Contagem();

        try (var extraidos = leitor.extrair(zip)) {
            for (Path shp : extraidos.shapefiles()) {
                log.info("Lendo {}", shp.getFileName());
                leitor.ler(shp, imovel -> contagem.adicionar(validador.validar(imovel), versao.id(), execucaoId));
            }
        }
        contagem.descarregar(versao.id(), execucaoId);

        gravador.concluir(arquivoId, execucaoId, versao.id(), contagem.lidos, contagem.validos, contagem.invalidos);
        return ProcessamentoResponse.processado(arquivoId, execucaoId, versao.versao(),
                contagem.lidos, contagem.validos, contagem.invalidos);
    }

    /** Acumula os resultados em lotes de TAMANHO_LOTE e grava quando enchem. */
    private class Contagem {
        final List<Resultado> aprovados = new ArrayList<>(TAMANHO_LOTE);
        final List<Resultado> reprovados = new ArrayList<>(TAMANHO_LOTE);
        int lidos;
        int validos;
        int invalidos;

        void adicionar(Resultado resultado, long datasetVersaoId, long execucaoId) {
            lidos++;
            if (resultado.aprovado()) {
                validos++;
                aprovados.add(resultado);
            } else {
                invalidos++;
                reprovados.add(resultado);
            }
            if (aprovados.size() >= TAMANHO_LOTE || reprovados.size() >= TAMANHO_LOTE) {
                descarregar(datasetVersaoId, execucaoId);
            }
            if (lidos % 10_000 == 0) {
                log.info("{} imóveis lidos", lidos);
            }
        }

        void descarregar(long datasetVersaoId, long execucaoId) {
            gravador.gravarImoveis(datasetVersaoId, aprovados);
            gravador.gravarQuarentena(execucaoId, reprovados);
            aprovados.clear();
            reprovados.clear();
        }
    }

    private static void apagar(Path arquivo) {
        if (arquivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(arquivo);
        } catch (Exception e) {
            log.warn("Não foi possível apagar o temporário {}", arquivo, e);
        }
    }
}
