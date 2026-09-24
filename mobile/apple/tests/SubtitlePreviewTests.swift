import Foundation

@main
struct SubtitlePreviewTests {
    static func main() {
        let vtt = """
        WEBVTT

        00:00:01.000 --> 00:00:03.000
        <i>Bonjour</i> tout le monde
        Comment allez-vous?

        00:00:04.000 --> 00:00:06.000
        Ça va bien &amp; vous?

        00:00:07.000 --> 00:00:09.000
        Au revoir

        00:00:10.000 --> 00:00:12.000
        Not shown
        """
        precondition(SubtitlePreview.parse(vtt) ==
                     "Bonjour tout le monde Comment allez-vous? • Ça va bien & vous? • Au revoir")
        precondition(SubtitlePreview.parseSample(vtt)?.languageText.contains("Not shown") == true,
                     "Language detection should receive more cues than the visible preview")

        let srt = """
        1
        00:00:01,000 --> 00:00:02,000
        Hello there

        2
        00:00:03,000 --> 00:00:04,000
        General Kenobi
        """
        precondition(SubtitlePreview.parse(srt) == "Hello there • General Kenobi")
        precondition(SubtitlePreview.parse("WEBVTT\n\nNOTE metadata\n") == nil)
        precondition(SubtitlePreview.parse("WEBVTT\n\n1\nA malformed first cue\nAnother line") ==
                     "A malformed first cue • Another line")
        precondition(SubtitleLanguageDetector.detect("Hello") == nil)
        precondition(SubtitleLanguageDetector.likelyLanguageCode([("fr", 0.89), ("en", 0.08)]) == "fr")
        precondition(SubtitleLanguageDetector.likelyLanguageCode([("fr", 0.68), ("en", 0.20)]) == nil)
        precondition(SubtitleLanguageDetector.likelyLanguageCode([("fr", 0.76), ("en", 0.68)]) == nil)
        precondition(SubtitleLanguageDetector.likelyLanguageCode([("und", 0.9)]) == nil)
        precondition(SubtitleLanguageDetector.likelyLanguageCode([("und", 0.9), ("fr", 0.8)]) == nil)
        print("Subtitle preview parser checks passed")
    }
}
