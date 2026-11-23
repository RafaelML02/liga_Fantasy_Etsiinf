package com.ligainternaetsiinf.service;

import java.time.Instant;
import java.time.ZoneId;

import java.util.List;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ligainternaetsiinf.dto.EstadisticasPartidoResponse;
import com.ligainternaetsiinf.dto.PartidoRequest;
import com.ligainternaetsiinf.dto.PartidoResponse;
import com.ligainternaetsiinf.dto.ResultadoRequest;
import com.ligainternaetsiinf.model.AlineacionEquipoJornada;
import com.ligainternaetsiinf.model.Equipo;
import com.ligainternaetsiinf.model.EquipoFantasy;
import com.ligainternaetsiinf.model.EstadisticasJugadorPartido;
import com.ligainternaetsiinf.model.Jugador;
import com.ligainternaetsiinf.model.JugadorFantasy;
import com.ligainternaetsiinf.model.Partido;
import com.ligainternaetsiinf.repository.AlineacionEquipoJornadaRepository;
import com.ligainternaetsiinf.repository.EquipoFantasyRepository;
import com.ligainternaetsiinf.repository.EquipoRepository;
import com.ligainternaetsiinf.repository.EstadisticasJugadorPartidoRepository;

import com.ligainternaetsiinf.repository.JugadorRepository;
import com.ligainternaetsiinf.repository.PartidoRepository;

@Service
public class PartidoService {

    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JugadorRepository jugadorRepository;

    @Autowired private EquipoFantasyRepository equipoFantasyRepository;
    @Autowired private EstadisticasJugadorPartidoRepository estadisticasRepository;
    @Autowired private EquipoRepository equipoRepository;
    @Autowired private EquipoFantasyService equipoFantasyService;
    @Autowired private AlineacionEquipoJornadaRepository alineacionRepository;
    @Autowired private TaskScheduler taskScheduler;
    @Autowired private PuntosFantasyService puntosFantasyService;

    public PartidoResponse crearPartido(PartidoRequest request) {
        Equipo local = equipoRepository.findById(request.getEquipoLocalId())
            .orElseThrow(() -> new RuntimeException("Equipo local no encontrado"));
        Equipo visitante = equipoRepository.findById(request.getEquipoVisitanteId())
            .orElseThrow(() -> new RuntimeException("Equipo visitante no encontrado"));

        if (local.getId().equals(visitante.getId())) {
            throw new RuntimeException("Un equipo no puede jugar contra sí mismo");
        }

        // Validar que ninguno de los dos equipos juega ya en esta jornada
        validarEquiposEnJornada(request.getEquipoLocalId(), request.getEquipoVisitanteId(),
            request.getJornada(), null);

        Partido partido = new Partido(local, visitante, request.getFecha(), request.getJornada());
        partidoRepository.save(partido);
        return toResponse(partido);
    }

    public PartidoResponse editarPartido(Integer id, PartidoRequest request) {
        Partido partido = partidoRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Partido no encontrado"));

        Equipo local = equipoRepository.findById(request.getEquipoLocalId())
            .orElseThrow(() -> new RuntimeException("Equipo local no encontrado"));
        Equipo visitante = equipoRepository.findById(request.getEquipoVisitanteId())
            .orElseThrow(() -> new RuntimeException("Equipo visitante no encontrado"));

        if (local.getId().equals(visitante.getId())) {
            throw new RuntimeException("Un equipo no puede jugar contra sí mismo");
        }

        validarEquiposEnJornada(request.getEquipoLocalId(), request.getEquipoVisitanteId(),
            request.getJornada(), id);

        // Con resultado, cambiar equipos o jornada descuadraría los puntos ya repartidos
        if (partido.isJugado() && (!partido.getEquipoLocal().getId().equals(local.getId())
                || !partido.getEquipoVisitante().getId().equals(visitante.getId())
                || !java.util.Objects.equals(partido.getJornada(), request.getJornada()))) {
            throw new RuntimeException("Quita el resultado antes de cambiar los equipos o la jornada de este partido");
        }

        partido.setEquipoLocal(local);
        partido.setEquipoVisitante(visitante);
        partido.setFecha(request.getFecha());
        partido.setJornada(request.getJornada());
        partidoRepository.save(partido);
        return toResponse(partido);
    }

    private void validarEquiposEnJornada(Integer localId, Integer visitanteId,
            Integer jornada, Integer partidoIdExcluir) {
        List<Partido> partidos = partidoRepository.findByJornada(jornada);
        for (Partido p : partidos) {
            if (partidoIdExcluir != null && p.getId().equals(partidoIdExcluir)) continue;
            if (p.getEquipoLocal().getId().equals(localId) ||
                p.getEquipoLocal().getId().equals(visitanteId) ||
                p.getEquipoVisitante().getId().equals(localId) ||
                p.getEquipoVisitante().getId().equals(visitanteId)) {
                throw new RuntimeException("Uno de los equipos ya tiene partido en esta jornada");
            }
        }
    }

    @Transactional
    public void eliminarPartido(Integer id) {
        Partido partido = partidoRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Partido no encontrado"));

        // Deshacer todo lo que sumó su resultado antes de borrarlo
        if (partido.isJugado()) {
            revertirResultado(partido);
        }
        estadisticasRepository.deleteAll(estadisticasRepository.findByPartidoId(id));
        partidoRepository.delete(partido);
    }

    public List<PartidoResponse> listarPartidos() {
        return partidoRepository.findAll()
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public List<PartidoResponse> listarPartidosPorJornada(Integer jornada) {
        return partidoRepository.findByJornada(jornada)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * Mete o corrige el resultado de un partido. Si ya tenía resultado, primero
     * se resta todo lo que aportó (clasificación, estadísticas y puntos de los
     * jugadores, puntos fantasy) y después se aplica el nuevo.
     */
    @Transactional
    public PartidoResponse registrarResultado(Integer id, ResultadoRequest request) {
        Partido partido = partidoRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Partido no encontrado"));

        validarResultado(request);

        if (partido.isJugado()) {
            revertirResultado(partido);
        }
        aplicarResultado(partido, request);

        return toResponse(partido);
    }

    /** Quita el resultado: el partido vuelve a "sin jugar" y se resta todo lo que sumó. */
    @Transactional
    public PartidoResponse quitarResultado(Integer id) {
        Partido partido = partidoRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Partido no encontrado"));
        if (!partido.isJugado()) {
            throw new RuntimeException("Este partido no tiene resultado");
        }
        revertirResultado(partido);
        return toResponse(partido);
    }

    private void validarResultado(ResultadoRequest request) {
        if (request.getGolesLocal() == null || request.getGolesVisitante() == null
                || request.getGolesLocal() < 0 || request.getGolesVisitante() < 0) {
            throw new RuntimeException("El marcador no es válido");
        }
        if (request.getEstadisticas() == null) return;
        java.util.Set<Integer> vistos = new java.util.HashSet<>();
        for (ResultadoRequest.EstadisticaJugadorRequest stat : request.getEstadisticas()) {
            if (!stat.isJugo()) continue;
            if (stat.getJugadorId() == null || !vistos.add(stat.getJugadorId())) {
                throw new RuntimeException("Jugador repetido o sin id en las estadísticas");
            }
            if (stat.getGoles() < 0 || stat.getAsistencias() < 0 || stat.getTarjetasAmarillas() < 0
                    || stat.getTarjetasRojas() < 0 || stat.getParadas() < 0) {
                throw new RuntimeException("Las estadísticas no pueden ser negativas");
            }
        }
    }

    /** Suma el resultado: clasificación, estadísticas por jugador y puntos fantasy. */
    private void aplicarResultado(Partido partido, ResultadoRequest request) {
        int golesLocal = request.getGolesLocal();
        int golesVisitante = request.getGolesVisitante();

        partido.setGolesLocal(golesLocal);
        partido.setGolesVisitante(golesVisitante);
        partido.setJugado(true);
        partidoRepository.save(partido);

        actualizarClasificacion(partido.getEquipoLocal(), golesLocal, golesVisitante, +1);
        actualizarClasificacion(partido.getEquipoVisitante(), golesVisitante, golesLocal, +1);

        List<ResultadoRequest.EstadisticaJugadorRequest> estadisticas =
            request.getEstadisticas() != null ? request.getEstadisticas() : List.of();

        for (ResultadoRequest.EstadisticaJugadorRequest stat : estadisticas) {
            if (!stat.isJugo()) continue;

            Jugador jugador = jugadorRepository.findById(stat.getJugadorId())
                .orElseThrow(() -> new RuntimeException("Jugador no encontrado: " + stat.getJugadorId()));

            String posicionUsada = (stat.getPosicionPartido() != null && !stat.getPosicionPartido().isBlank())
                ? stat.getPosicionPartido()
                : jugador.getPosicion();
            int puntos = calcularPuntosJugador(posicionUsada, stat, golesLocal, golesVisitante, partido);

            EstadisticasJugadorPartido estadistica = estadisticasRepository
                .findByJugadorIdAndPartidoId(jugador.getId(), partido.getId())
                .orElse(new EstadisticasJugadorPartido(jugador, partido));

            estadistica.setJugo(true);
            estadistica.setGoles(stat.getGoles());
            estadistica.setAsistencias(stat.getAsistencias());
            estadistica.setTarjetasAmarillas(stat.getTarjetasAmarillas());
            estadistica.setTarjetasRojas(stat.getTarjetasRojas());
            estadistica.setParadas(stat.getParadas());
            estadistica.setPuntosObtenidos(puntos);
            estadistica.setPosicionJugada(posicionUsada);
            estadisticasRepository.save(estadistica);

            actualizarAcumuladosJugador(jugador, estadistica, +1);
        }

        // Puntos a los equipos fantasy con alineación ya registrada en esta jornada.
        // Si la jornada aún no ha empezado, se sumarán al congelar las alineaciones.
        puntosFantasyService.aplicarPartido(partido, +1);
    }

    /** Resta exactamente lo que sumó el resultado guardado y deja el partido sin jugar. */
    private void revertirResultado(Partido partido) {
        // Primero los puntos fantasy, mientras las estadísticas siguen guardadas
        puntosFantasyService.aplicarPartido(partido, -1);

        int golesLocal = partido.getGolesLocal() != null ? partido.getGolesLocal() : 0;
        int golesVisitante = partido.getGolesVisitante() != null ? partido.getGolesVisitante() : 0;
        actualizarClasificacion(partido.getEquipoLocal(), golesLocal, golesVisitante, -1);
        actualizarClasificacion(partido.getEquipoVisitante(), golesVisitante, golesLocal, -1);

        List<EstadisticasJugadorPartido> stats = estadisticasRepository.findByPartidoId(partido.getId());
        for (EstadisticasJugadorPartido estadistica : stats) {
            if (estadistica.isJugo()) {
                Jugador jugador = estadistica.getJugador();
                actualizarAcumuladosJugador(jugador, estadistica, -1);
            }
        }
        estadisticasRepository.deleteAll(stats);
        estadisticasRepository.flush();

        partido.setGolesLocal(null);
        partido.setGolesVisitante(null);
        partido.setJugado(false);
        partidoRepository.save(partido);
    }

    /** Suma (signo = +1) o resta (signo = -1) un partido a la clasificación de un equipo. */
    private void actualizarClasificacion(Equipo equipo, int golesFavor, int golesContra, int signo) {
        equipo.setGolesAFavor(equipo.getGolesAFavor() + signo * golesFavor);
        equipo.setGolesEnContra(equipo.getGolesEnContra() + signo * golesContra);
        equipo.setDiferenciaDeGoles(equipo.getGolesAFavor() - equipo.getGolesEnContra());
        equipo.setPartidosJugados(equipo.getPartidosJugados() + signo);
        int puntos = golesFavor > golesContra ? 3 : (golesFavor == golesContra ? 1 : 0);
        equipo.setPuntos(equipo.getPuntos() + signo * puntos);
        equipoRepository.save(equipo);
    }

    /** Suma (signo = +1) o resta (signo = -1) las estadísticas de un partido a los totales del jugador. */
    private void actualizarAcumuladosJugador(Jugador jugador, EstadisticasJugadorPartido e, int signo) {
        jugador.setPuntosFantasy(jugador.getPuntosFantasy() + signo * e.getPuntosObtenidos());
        jugador.setGoles(jugador.getGoles() + signo * e.getGoles());
        jugador.setAsistencias(jugador.getAsistencias() + signo * e.getAsistencias());
        jugador.setTarjetasAmarillas(jugador.getTarjetasAmarillas() + signo * e.getTarjetasAmarillas());
        jugador.setTarjetasRojas(jugador.getTarjetasRojas() + signo * e.getTarjetasRojas());
        jugador.setParadas(jugador.getParadas() + signo * e.getParadas());
        jugador.setPartidosJugados(jugador.getPartidosJugados() + signo);
        jugadorRepository.save(jugador);
    }

    private int calcularPuntosJugador(String posicion,
        ResultadoRequest.EstadisticaJugadorRequest stat,
        int golesLocal, int golesVisitante, Partido partido) {

        int puntos = 2; // puntos base por jugar

        // Determinar goles encajados por el equipo de este jugador
        boolean esLocal = partido.getEquipoLocal().getJugadores().stream()
            .anyMatch(j -> j.getId().equals(stat.getJugadorId()));
        int golesEncajados = esLocal ? golesVisitante : golesLocal;

        // Puntos por portería / goles encajados según posición
        switch (posicion) {
            case "PORTERO" -> {
                int base = 7;
                int resta = golesEncajados; // -1 por cada gol
                puntos += base-resta; // nunca resta más de lo que da
            }
            case "DEFENSA" -> {
                int base = 5;
                int resta = golesEncajados / 2; // -1 cada 2 goles
                puntos += base-resta;
            }
            case "MEDIOCENTRO" -> {
                int base = 2;
                int resta = golesEncajados / 3; // -1 cada 3 goles
                puntos += base-resta;
            }
            case "DELANTERO" -> {
                int base = 1;
                int resta = golesEncajados / 4; // -1 cada 4 goles
                puntos += base-resta;
            }
        }

        // Goles: portero/defensa 6pts, mediocentro 5pts, delantero 4pts
        int ptsGol = switch (posicion) {
            case "PORTERO", "DEFENSA" -> 6;
            case "MEDIOCENTRO"        -> 5;
            default                   -> 4;
        };
        puntos += stat.getGoles() * ptsGol;

        // Asistencias: 3pts
        puntos += stat.getAsistencias() * 3;

        // Tarjetas
        puntos -= stat.getTarjetasAmarillas();
        puntos -= stat.getTarjetasRojas() * 3;

        // Portero: paradas
        if ("PORTERO".equals(posicion)) {
            puntos += stat.getParadas();
        }

        return puntos; // puede ser negativo
    }

    // ─── Programar registro de alineaciones ──────────────────────────────────
    private void programarRegistroAlineaciones(Partido partido) {
        if (partido.getFecha() == null || partido.getJornada() == null) return;

        if (partido.getFecha().isBefore(java.time.LocalDateTime.now())) return;

        Instant fechaEjecucion = partido.getFecha()
            .atZone(ZoneId.of("Europe/Madrid"))
            .toInstant();

        Integer jornada = partido.getJornada();

        taskScheduler.schedule(() -> {
            boolean yaRegistrado = !alineacionRepository.findByJornada(jornada).isEmpty();
            if (!yaRegistrado) {
                System.out.println("Registrando alineaciones para jornada " + jornada);
                equipoFantasyService.registrarAlineacionesJornada(jornada);
            }
        }, fechaEjecucion);
    }

    public List<EstadisticasPartidoResponse> getEstadisticasPartido(Integer partidoId) {
        List<EstadisticasJugadorPartido> stats = estadisticasRepository.findByPartidoId(partidoId);
        return stats.stream()
            .filter(EstadisticasJugadorPartido::isJugo)
            .map(e -> new EstadisticasPartidoResponse(
                e.getJugador().getId(),
                e.getJugador().getFullName(),
                e.getJugador().getPosicion(),
                e.getJugador().getEquipo() != null ? e.getJugador().getEquipo().getName() : null,
                e.getGoles(), e.getAsistencias(),
                e.getTarjetasAmarillas(), e.getTarjetasRojas(),
                e.getParadas(), e.isJugo(),
                e.getPosicionJugada(), e.getPuntosObtenidos()
            ))
            .collect(java.util.stream.Collectors.toList());
    }

    private PartidoResponse toResponse(Partido p) {
        return new PartidoResponse(
            p.getId(),
            p.getEquipoLocal().getId(),
            p.getEquipoLocal().getName(),
            p.getEquipoVisitante().getId(),
            p.getEquipoVisitante().getName(),
            p.getFecha(),
            p.getJornada(),
            p.getGolesLocal(),
            p.getGolesVisitante(),
            p.isJugado()
        );
    }
}
