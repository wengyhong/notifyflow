export type CsrfToken = {
	headerName: string;
	token: string;
};

export async function getCsrfToken(): Promise<CsrfToken | null> {
	const response = await fetch('/api/auth/csrf', { cache: 'no-store' });

	if (!response.ok) return null;

	return response.json();
}
