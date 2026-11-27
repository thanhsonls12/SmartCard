package vn.labaccess.desktop.virtualreader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

public final class VirtualCardCatalog {
    public record Card(UUID id, String name, Path image) { }

    private final Path directory;

    public VirtualCardCatalog(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory.resolve("cards"));
    }

    public List<Card> load() throws IOException {
        List<Card> cards = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".properties")).toList()) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                try {
                    UUID id = UUID.fromString(file.getFileName().toString().replace(".properties", ""));
                    String name = properties.getProperty("name");
                    String image = properties.getProperty("image");
                    if (name == null || name.isBlank() || image == null || image.isBlank()) {
                        throw new IllegalArgumentException("Missing metadata");
                    }
                    Path path = Path.of(image);
                    if (!path.isAbsolute()) {
                        throw new IllegalArgumentException("Relative EEPROM path");
                    }
                    cards.add(new Card(id, name, path));
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Metadata thẻ không hợp lệ: " + file.getFileName(), failure);
                }
            }
        }
        cards.sort(Comparator.comparing(Card::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(cards);
    }

    public Card newCard(String name) throws IOException {
        validateName(name);
        UUID id = UUID.randomUUID();
        return new Card(id, name.strip(), directory.resolve("cards").resolve(id + ".eeprom"));
    }

    public Card existingCard(String name, Path image) throws IOException {
        validateName(name);
        Path real = image.toRealPath();
        if (!Files.isRegularFile(real) || Files.size(real) == 0) {
            throw new IOException("EEPROM không hợp lệ");
        }
        for (Card card : load()) {
            if (Files.exists(card.image()) && Files.isSameFile(card.image(), real)) {
                throw new IOException("EEPROM đã có trong danh sách");
            }
        }
        return new Card(UUID.randomUUID(), name.strip(), real);
    }

    private void validateName(String name) throws IOException {
        if (name == null || name.isBlank() || name.strip().length() > 60
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("Tên thẻ phải từ 1 đến 60 ký tự");
        }
        for (Card card : load()) {
            if (card.name().equalsIgnoreCase(name.strip())) {
                throw new IOException("Tên thẻ đã tồn tại");
            }
        }
    }

    public void save(Card card) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("name", card.name());
        properties.setProperty("image", card.image().toString());
        Path target = directory.resolve(card.id() + ".properties");
        Path temporary = directory.resolve(card.id() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            properties.store(writer, "LabAccess virtual card");
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
