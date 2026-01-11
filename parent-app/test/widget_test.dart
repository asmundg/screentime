import 'package:flutter_test/flutter_test.dart';
import 'package:parent_app/main.dart';

void main() {
  testWidgets('App builds successfully', (WidgetTester tester) async {
    await tester.pumpWidget(const ScreenTimeApp());
    // App should build without errors
    expect(find.byType(ScreenTimeApp), findsOneWidget);
  });
}
