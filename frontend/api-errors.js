async function apiErrorMessage(response, fallback) {
  try {
    const body = await response.json();
    if (body && typeof body.code === "string" && /^[a-z][a-z0-9_]*$/.test(body.code) &&
        typeof body.message === "string" && body.message.length > 0 && body.message.length <= 500) {
      return body.message;
    }
  } catch {
    // A proxy or older server may return HTML or a different error contract.
  }
  return fallback;
}
