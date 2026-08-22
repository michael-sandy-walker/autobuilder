package com.walpro.autobuilder.testsupport;

import java.util.List;
import java.util.Map;

/**
 * T5-AF layer 2: a labelled corpus of (criterion, hollow-impl, real-impl) triples drawn from the
 * 2026-06-16 order-checkout false completion — the corpus's first false COMPLETED, reached on a
 * hollow artifact ({@code OrderService.checkout → return null}, an empty {@code PriceBreakdown},
 * no exception→status mapping, orders never persisted) that the completion gate passed 18/18.
 *
 * <p>Each case is a controlled pair: the SAME acceptance criterion against a stub implementation
 * that does NOT satisfy it and a genuine implementation that does. Running both through the REAL
 * {@code CriteriaVerificationService} answers the one question nondeterminism leaves open — "does
 * the completion verifier (T4-BP-3) actually catch a hollow artifact?" — as a precision/recall
 * number that is re-runnable as the local model or the prompt changes.
 *
 * <p>Test-only data: target-project class names ({@code OrderService}, {@code PriceBreakdown}, …)
 * are fine here (fixtures), never in framework production strings.
 */
public final class HollowVsRealFixtures {

    private HollowVsRealFixtures() {}

    /**
     * One labelled case. {@code hollow} must be graded ✗ (criterion not satisfied) and {@code real}
     * graded ✓ by a correct verifier. The {@code subject} is the goal description shown alongside.
     */
    public record LabeledCase(String name, String subject, String criterion,
                              Map<String, String> hollow, Map<String, String> real) {
    }

    public static List<LabeledCase> all() {
        return List.of(computedPricing(), orderPersisted(), invalidCheckoutMaps4xx(), emptyCartZeroPricing(),
                unenforcedFieldValidation(), repositoryBeanMissing(), exceptionMappedButNeverThrown());
    }

    /**
     * T6-O: the two cases below come from OBSERVED field failures rather than invention, because a
     * synthetic case that does not match the real distribution manufactures false findings.
     *
     * <p>Scope note: only the WIRING family maps onto this corpus. The other measured failure
     * family — a type the code references that nobody ever wrote — never reaches the verifier at
     * all, because the compiler rejects it first. A hollow/real pair must COMPILE on both sides,
     * so a missing type cannot be expressed here; it belongs to the build-gate chain instead.
     */

    private static final String SUBJECT =
            "A REST checkout endpoint: POST /carts/{id}/checkout prices the cart, persists an order, and maps failures to HTTP status codes.";

    /**
     * From run {@code 2026-08-22_06-19-55}: the run ended {@code boot-failed} with
     * "No qualifying bean of type 'BookRepository' available" — the artifact COMPILED and passed
     * every compile gate, then failed at context startup. The hollow side is a plain interface
     * that no Spring Data proxy will ever implement and nothing registers as a bean; the code
     * reads as finished and cannot persist anything.
     */
    public static LabeledCase repositoryBeanMissing() {
        String criterion = "POST /carts/{id}/checkout persists the order so a later read returns it (the repository is a real, registered bean).";
        Map<String, String> hollow = Map.of(
                "src/main/java/com/example/demo/OrderRepository.java", """
                        package com.example.demo;
                        import java.util.Optional;
                        // Declared, never implemented, and not a Spring Data interface: no proxy is
                        // generated and no bean is registered, so injection fails at startup.
                        public interface OrderRepository {
                            Order save(Order order);
                            Optional<Order> findById(Long id);
                        }
                        """,
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            private final OrderRepository orders;
                            public OrderService(OrderRepository orders) { this.orders = orders; }
                            public Order checkout(Long cartId) {
                                Order o = new Order(cartId);
                                return orders.save(o);
                            }
                        }
                        """);
        Map<String, String> real = Map.of(
                "src/main/java/com/example/demo/OrderRepository.java", """
                        package com.example.demo;
                        import org.springframework.data.jpa.repository.JpaRepository;
                        import org.springframework.stereotype.Repository;
                        @Repository
                        public interface OrderRepository extends JpaRepository<Order, Long> {
                        }
                        """,
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            private final OrderRepository orders;
                            public OrderService(OrderRepository orders) { this.orders = orders; }
                            public Order checkout(Long cartId) {
                                Order o = new Order(cartId);
                                return orders.save(o);
                            }
                        }
                        """);
        return new LabeledCase("repository-bean-missing", SUBJECT, criterion, hollow, real);
    }

    /**
     * The {@code T4-DC} shape, filed from the field: a handler maps an exception type the service
     * never throws, so the mapping exists, reads as complete, and every real failure still falls
     * through to a 500. The hollow side is deliberately harder than a missing handler — the
     * handler is PRESENT and correct-looking, and only the throw site is absent.
     */
    public static LabeledCase exceptionMappedButNeverThrown() {
        String criterion = "Checking out a cart that is already checked out returns 409 CONFLICT (the mapped exception is actually thrown).";
        Map<String, String> hollow = Map.of(
                "src/main/java/com/example/demo/CheckoutExceptionHandler.java", """
                        package com.example.demo;
                        import org.springframework.http.HttpStatus;
                        import org.springframework.http.ResponseEntity;
                        import org.springframework.web.bind.annotation.ExceptionHandler;
                        import org.springframework.web.bind.annotation.RestControllerAdvice;
                        @RestControllerAdvice
                        public class CheckoutExceptionHandler {
                            @ExceptionHandler(CartAlreadyCheckedOutException.class)
                            public ResponseEntity<String> handle(CartAlreadyCheckedOutException e) {
                                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
                            }
                        }
                        """,
                "src/main/java/com/example/demo/CartAlreadyCheckedOutException.java", """
                        package com.example.demo;
                        public class CartAlreadyCheckedOutException extends RuntimeException {
                            public CartAlreadyCheckedOutException(String m) { super(m); }
                        }
                        """,
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            // Nothing here ever throws CartAlreadyCheckedOutException, so the
                            // handler above can never fire and a re-checkout surfaces as a 500.
                            public Order checkout(Cart cart) {
                                return new Order(cart.getId());
                            }
                        }
                        """);
        Map<String, String> real = Map.of(
                "src/main/java/com/example/demo/CheckoutExceptionHandler.java", """
                        package com.example.demo;
                        import org.springframework.http.HttpStatus;
                        import org.springframework.http.ResponseEntity;
                        import org.springframework.web.bind.annotation.ExceptionHandler;
                        import org.springframework.web.bind.annotation.RestControllerAdvice;
                        @RestControllerAdvice
                        public class CheckoutExceptionHandler {
                            @ExceptionHandler(CartAlreadyCheckedOutException.class)
                            public ResponseEntity<String> handle(CartAlreadyCheckedOutException e) {
                                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
                            }
                        }
                        """,
                "src/main/java/com/example/demo/CartAlreadyCheckedOutException.java", """
                        package com.example.demo;
                        public class CartAlreadyCheckedOutException extends RuntimeException {
                            public CartAlreadyCheckedOutException(String m) { super(m); }
                        }
                        """,
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            public Order checkout(Cart cart) {
                                if (cart.isCheckedOut()) {
                                    throw new CartAlreadyCheckedOutException("cart " + cart.getId() + " already checked out");
                                }
                                cart.markCheckedOut();
                                return new Order(cart.getId());
                            }
                        }
                        """);
        return new LabeledCase("exception-mapped-never-thrown", SUBJECT, criterion, hollow, real);
    }

    /** The headline false-completion criterion: checkout returns a COMPUTED price breakdown. */
    public static LabeledCase computedPricing() {
        String criterion = "POST /carts/{id}/checkout returns an OrderResponse whose PriceBreakdown has a computed subtotal, tax, and total (not zero/null).";
        Map<String, String> hollow = Map.of(
                "src/main/java/com/example/demo/PriceBreakdown.java", """
                        package com.example.demo;
                        public class PriceBreakdown {
                            private double subtotal;
                            private double tax;
                            private double total;
                            // getters/setters omitted
                        }
                        """,
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            public OrderResponse checkout(Long cartId) {
                                // TODO: compute pricing
                                return null;
                            }
                            private PriceBreakdown calculatePriceBreakdown(Cart cart) {
                                return new PriceBreakdown();
                            }
                        }
                        """);
        Map<String, String> real = Map.of(
                "src/main/java/com/example/demo/PriceBreakdown.java", """
                        package com.example.demo;
                        public class PriceBreakdown {
                            private final double subtotal;
                            private final double tax;
                            private final double total;
                            public PriceBreakdown(double subtotal, double tax, double total) {
                                this.subtotal = subtotal; this.tax = tax; this.total = total;
                            }
                            public double getSubtotal() { return subtotal; }
                            public double getTax() { return tax; }
                            public double getTotal() { return total; }
                        }
                        """,
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            public OrderResponse checkout(Long cartId) {
                                Cart cart = cartRepository.findById(cartId).orElseThrow();
                                PriceBreakdown price = calculatePriceBreakdown(cart);
                                Order order = new Order(cart, price);
                                orderRepository.save(order);
                                return new OrderResponse(order.getId(), price);
                            }
                            private PriceBreakdown calculatePriceBreakdown(Cart cart) {
                                double subtotal = cart.getItems().stream()
                                        .mapToDouble(i -> i.getPrice() * i.getQuantity()).sum();
                                double tax = subtotal * 0.20;
                                return new PriceBreakdown(subtotal, tax, subtotal + tax);
                            }
                        }
                        """);
        return new LabeledCase("computed-pricing", SUBJECT, criterion, hollow, real);
    }

    /** Checkout PERSISTS the order (saveOrder actually called vs never wired). */
    public static LabeledCase orderPersisted() {
        String criterion = "Checking out persists the created Order via the order repository (the order is saved, not discarded).";
        Map<String, String> hollow = Map.of(
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            private final OrderRepository orderRepository;
                            public OrderService(OrderRepository orderRepository) { this.orderRepository = orderRepository; }
                            public OrderResponse checkout(Long cartId) {
                                Order order = new Order(cartId);
                                // order is built but never saved
                                return new OrderResponse(order.getId());
                            }
                        }
                        """);
        Map<String, String> real = Map.of(
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            private final OrderRepository orderRepository;
                            public OrderService(OrderRepository orderRepository) { this.orderRepository = orderRepository; }
                            public OrderResponse checkout(Long cartId) {
                                Order order = new Order(cartId);
                                Order saved = orderRepository.save(order);
                                return new OrderResponse(saved.getId());
                            }
                        }
                        """);
        return new LabeledCase("order-persisted", SUBJECT, criterion, hollow, real);
    }

    /** Invalid checkout maps to a 4xx status (exception handling present vs absent). */
    public static LabeledCase invalidCheckoutMaps4xx() {
        String criterion = "Checking out an invalid/missing cart returns a 4xx HTTP status (the error is mapped to a client error, not a 500/null).";
        Map<String, String> hollow = Map.of(
                "src/main/java/com/example/demo/OrderController.java", """
                        package com.example.demo;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class OrderController {
                            private final OrderService orderService;
                            public OrderController(OrderService orderService) { this.orderService = orderService; }
                            @PostMapping("/carts/{id}/checkout")
                            public OrderResponse checkout(@PathVariable Long id) {
                                return orderService.checkout(id); // throws bubble up as 500
                            }
                        }
                        """);
        Map<String, String> real = Map.of(
                "src/main/java/com/example/demo/OrderController.java", """
                        package com.example.demo;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class OrderController {
                            private final OrderService orderService;
                            public OrderController(OrderService orderService) { this.orderService = orderService; }
                            @PostMapping("/carts/{id}/checkout")
                            public OrderResponse checkout(@PathVariable Long id) {
                                return orderService.checkout(id);
                            }
                        }
                        """,
                "src/main/java/com/example/demo/ApiExceptionHandler.java", """
                        package com.example.demo;
                        import org.springframework.http.HttpStatus;
                        import org.springframework.web.bind.annotation.*;
                        @RestControllerAdvice
                        public class ApiExceptionHandler {
                            @ExceptionHandler(CartNotFoundException.class)
                            @ResponseStatus(HttpStatus.NOT_FOUND)
                            public String handleMissingCart(CartNotFoundException e) { return e.getMessage(); }
                        }
                        """);
        return new LabeledCase("invalid-checkout-4xx", SUBJECT, criterion, hollow, real);
    }

    /** Empty cart returns zero pricing (handled vs unhandled NPE path). */
    public static LabeledCase emptyCartZeroPricing() {
        String criterion = "Checking out an empty cart returns a PriceBreakdown of zero (subtotal/tax/total all 0), not an error or null.";
        Map<String, String> hollow = Map.of(
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            private PriceBreakdown calculatePriceBreakdown(Cart cart) {
                                // assumes a non-empty cart; the first() call NPEs on an empty one
                                Item first = cart.getItems().get(0);
                                double subtotal = first.getPrice() * first.getQuantity();
                                return new PriceBreakdown(subtotal, subtotal * 0.2, subtotal * 1.2);
                            }
                        }
                        """);
        Map<String, String> real = Map.of(
                "src/main/java/com/example/demo/OrderService.java", """
                        package com.example.demo;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class OrderService {
                            private PriceBreakdown calculatePriceBreakdown(Cart cart) {
                                if (cart.getItems().isEmpty()) {
                                    return new PriceBreakdown(0, 0, 0);
                                }
                                double subtotal = cart.getItems().stream()
                                        .mapToDouble(i -> i.getPrice() * i.getQuantity()).sum();
                                return new PriceBreakdown(subtotal, subtotal * 0.2, subtotal * 1.2);
                            }
                        }
                        """);
        return new LabeledCase("empty-cart-zero-pricing", SUBJECT, criterion, hollow, real);
    }

    /**
     * The run-5 (2026-07-02) measured verifier false-pass: a "returns 400 on invalid input"
     * criterion graded ✓ while the request body carries no constraint annotations and the binding
     * has no validation trigger — runtime returns 201. Hollow = no validation surface at all;
     * real = constrained fields + @Valid on the binding.
     */
    public static LabeledCase unenforcedFieldValidation() {
        String criterion = "POST /carts/{id}/checkout returns 400 when the CheckoutRequest is invalid "
                + "(couponCode not matching [A-Z0-9]{8}, or missing paymentMethod).";
        Map<String, String> hollow = Map.of(
                "src/main/java/com/example/demo/CheckoutRequest.java", """
                        package com.example.demo;
                        public class CheckoutRequest {
                            private String couponCode;
                            private String paymentMethod;
                            public String getCouponCode() { return couponCode; }
                            public String getPaymentMethod() { return paymentMethod; }
                            public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
                            public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
                        }
                        """,
                "src/main/java/com/example/demo/OrderController.java", """
                        package com.example.demo;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class OrderController {
                            private final OrderService orderService;
                            public OrderController(OrderService orderService) { this.orderService = orderService; }
                            @PostMapping("/carts/{id}/checkout")
                            public OrderResponse checkout(@PathVariable Long id, @RequestBody CheckoutRequest request) {
                                return orderService.checkout(id, request); // no validation anywhere: bad input → 201
                            }
                        }
                        """);
        Map<String, String> real = Map.of(
                "src/main/java/com/example/demo/CheckoutRequest.java", """
                        package com.example.demo;
                        import jakarta.validation.constraints.NotBlank;
                        import jakarta.validation.constraints.Pattern;
                        public class CheckoutRequest {
                            @Pattern(regexp = "[A-Z0-9]{8}")
                            private String couponCode;
                            @NotBlank
                            private String paymentMethod;
                            public String getCouponCode() { return couponCode; }
                            public String getPaymentMethod() { return paymentMethod; }
                            public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
                            public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
                        }
                        """,
                "src/main/java/com/example/demo/OrderController.java", """
                        package com.example.demo;
                        import jakarta.validation.Valid;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class OrderController {
                            private final OrderService orderService;
                            public OrderController(OrderService orderService) { this.orderService = orderService; }
                            @PostMapping("/carts/{id}/checkout")
                            public OrderResponse checkout(@PathVariable Long id, @Valid @RequestBody CheckoutRequest request) {
                                return orderService.checkout(id, request);
                            }
                        }
                        """);
        return new LabeledCase("unenforced-field-validation", SUBJECT, criterion, hollow, real);
    }
}
