package com.github.gis.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

public record ApiError(String code, String message) {
  public static ApiError forStatus(int status) {
    return switch (status) {
      case 400 -> new ApiError("invalid_request", "Некорректный JSON, поля или типы запроса.");
      case 401 -> new ApiError("authentication_required", "Для этой операции требуется вход.");
      case 403 -> new ApiError("forbidden", "Доступ к операции запрещён.");
      case 404 -> new ApiError("not_found", "Ресурс не найден.");
      case 405 ->
          new ApiError("method_not_allowed", "HTTP-метод не поддерживается для этого адреса.");
      case 406 -> new ApiError("not_acceptable", "Запрошенный формат ответа не поддерживается.");
      case 413 -> new ApiError("payload_too_large", "Запрос слишком велик.");
      case 415 ->
          new ApiError("unsupported_media_type", "Требуется Content-Type application/json.");
      case 503 -> new ApiError("service_unavailable", "Сервис временно недоступен.");
      default ->
          status >= 400 && status < 500
              ? new ApiError("invalid_request", "Запрос отклонён.")
              : new ApiError("internal_error", "Ошибка сервера при обработке запроса.");
    };
  }

  public void write(ObjectMapper mapper, HttpServletResponse response, int status)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.getOutputStream().write(mapper.writeValueAsBytes(this));
  }
}
