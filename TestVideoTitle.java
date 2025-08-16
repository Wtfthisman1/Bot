import java.io.IOException;

public class TestVideoTitle {
    
    public static void main(String[] args) {
        StorageManager storageManager = new StorageManager();
        
        // Тестируем с валидным YouTube URL
        String testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        
        try {
            System.out.println("Тестируем получение названия для: " + testUrl);
            String title = storageManager.videoTitle(testUrl);
            System.out.println("Полученное название: '" + title + "'");
        } catch (IOException e) {
            System.err.println("Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
