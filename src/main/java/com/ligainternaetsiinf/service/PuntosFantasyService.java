package com.ligainternaetsiinf.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ligainternaetsiinf.model.AlineacionEquipoJornada;
import com.ligainternaetsiinf.model.EquipoFantasy;
import com.ligainternaetsiinf.model.EstadisticasJugadorPartido;
import com.ligainternaetsiinf.model.JugadorFantasy;
import com.ligainternaetsiinf.model.Partido;
import com.ligainternaetsiinf.repository.AlineacionEquipoJornadaRepository;
import com.ligainternaetsiinf.repository.EquipoFantasyRepository;
import com.ligainternaetsiinf.repository.EstadisticasJugadorPartidoRepository;
import com.ligainternaetsiinf.repository.PartidoRepository;

/**
 * Reparto de los puntos de un partido entre los equipos fantasy.
 *
 * Regla única: lo que un partido aporta a una alineación es la suma de los
 * puntos obtenidos en ese partido por los jugadores reales que estaban
 * alineados (si la alineación puntúa). Se usa la misma regla para sumar y para
 * restar, de modo que corregir un resultado (restar lo viejo y sumar lo nuevo)
 * deja los puntos exactos.
 */
@Service
public class PuntosFantasyService {

    @Autowired private AlineacionEquipoJornadaRepository alineacionRepository;
    @Autowired private EstadisticasJugadorPartidoRepository estadisticasRepository;
    @Autowired private EquipoFantasyRepository equipoFantasyRepository;
    @Autowired private PartidoRepository partidoRepository;

    /** Puntos que aportan unas estadísticas de partido a una alineación. */
    public int puntosDeAlineacion(AlineacionEquipoJornada alineacion,
                                  List<EstadisticasJugadorPartido> stats) {
        if (!alineacion.isPuntua() || alineacion.getJugadoresAlineados() == null) return 0;

        Set<Integer> idsAlineados = alineacion.getJugadoresAlineados().stream()
            .map(JugadorFantasy::getJugadorReal)
            .filter(j -> j != null)
            .map(j -> j.getId())
            .collect(Collectors.toSet());

        return stats.stream()
            .filter(EstadisticasJugadorPartido::isJugo)
            .filter(e -> idsAlineados.contains(e.getJugador().getId()))
            .mapToInt(EstadisticasJugadorPartido::getPuntosObtenidos)
            .sum();
    }

    /**
     * Suma (signo = +1) o resta (signo = -1) a cada equipo fantasy los puntos
     * del partido según las alineaciones registradas para su jornada.
     * Debe llamarse con las estadísticas del partido todavía guardadas.
     */
    public void aplicarPartido(Partido partido, int signo) {
        List<EstadisticasJugadorPartido> stats = estadisticasRepository.findByPartidoId(partido.getId());
        if (stats.isEmpty()) return;

        for (AlineacionEquipoJornada alineacion : alineacionRepository.findByJornada(partido.getJornada())) {
            int puntos = puntosDeAlineacion(alineacion, stats);
            if (puntos == 0) continue;
            EquipoFantasy equipo = alineacion.getEquipoFantasy();
            equipo.setPuntos(equipo.getPuntos() + signo * puntos);
            equipoFantasyRepository.save(equipo);
        }
    }

    /**
     * Al congelar una alineación, le suma los puntos de los partidos de su
     * jornada que ya tengan resultado (resultados metidos antes del inicio).
     */
    public void aplicarPartidosYaJugados(AlineacionEquipoJornada alineacion) {
        if (!alineacion.isPuntua()) return;

        int total = 0;
        for (Partido partido : partidoRepository.findByJornada(alineacion.getJornada())) {
            if (!partido.isJugado()) continue;
            total += puntosDeAlineacion(alineacion, estadisticasRepository.findByPartidoId(partido.getId()));
        }
        if (total == 0) return;

        EquipoFantasy equipo = alineacion.getEquipoFantasy();
        equipo.setPuntos(equipo.getPuntos() + total);
        equipoFantasyRepository.save(equipo);
    }
}
