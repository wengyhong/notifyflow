export const deliveryStatuses = ['PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'DELIVERED', 'FAILED'] as const;

export type DeliveryStatus = (typeof deliveryStatuses)[number];

export type Delivery = {
	id: string;
	eventId: string;
	endpointId: string;
	status: DeliveryStatus;
	createdAt: string;
	nextAttemptAt: string | null;
	retryCycleStartAttempt: number;
};

export type DeliveryAttempt = {
	id: string;
	deliveryId: string;
	attemptNumber: number;
	startedAt: string;
	completedAt: string | null;
	responseStatus: number | null;
	errorMessage: string | null;
};

type Page<T> = {
	content: T[];
	page: number;
	size: number;
	totalElements: number;
	totalPages: number;
};

export type DeliveryPage = Page<Delivery>;
export type DeliveryAttemptPage = Page<DeliveryAttempt>;
