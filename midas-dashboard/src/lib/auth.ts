/**
 * Shared auth constants — safe to import from both Server and Client Components.
 * No server-only APIs (next/headers, next/navigation) are used here.
 */

/** Cookie name that stores the MIDAS JWT issued via the Telegram Magic Link. */
export const TOKEN_COOKIE_NAME = 'midas_token'
