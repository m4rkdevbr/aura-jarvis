package app.dzz.copilot.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionsTest {
    @Test
    fun picksTheApp() {
        assertEquals(AppActions.Target.YouTube, AppActions.target("YouTube"))
        assertEquals(AppActions.Target.YouTubeMusic, AppActions.target("youtube music"))
        assertEquals(AppActions.Target.Spotify, AppActions.target("Spotify"))
        assertEquals(AppActions.Target.Route, AppActions.target("rota"))
        assertEquals(AppActions.Target.Maps, AppActions.target("Maps"))
        assertEquals(AppActions.Target.PlayStore, AppActions.target("play store"))
        assertNull(AppActions.target("banco"))
    }

    @Test
    fun neverTapsSensitiveButtons() {
        assertTrue(AppActions.sensitive("Enviar"))
        assertTrue(AppActions.sensitive("Confirmar pagamento"))
        assertTrue(AppActions.sensitive("Inscrever-se"))
        assertTrue(AppActions.sensitive("Excluir"))
        assertFalse(AppActions.sensitive("Receita de pão caseiro fácil"))
    }

    @Test
    fun recognisesVideoCards() {
        assertTrue(AppActions.looksLikeVideo("Pão caseiro - 10 minutos e 3 segundos - Canal X - 1,2 mi de visualizações - reproduzir vídeo"))
        assertFalse(AppActions.looksLikeVideo("Filtros"))
    }
}
