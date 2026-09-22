#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=============================================================================
DỰ ÁN KHOA HỌC KỸ THUẬT: CVA-SMARTGUARDIAN (HỆ THỐNG ĐỒNG HÀNH & BẢO VỆ SỐ)
MODULE: AI DỰ BÁO SỚM NGUY CƠ NGHIỆN SỐ (DIGITAL WELLBEING AI ENGINE)
=============================================================================
Cơ sở khoa học:
1. Thang đo Smartphone Addiction Scale - Short Version (SAS-SV) - Kwon et al., PLOS ONE (2013).
2. Thang đo Internet Addiction Test (IAT) - Dr. Kimberly Young (1998).
3. Chuẩn kỹ thuật Edge AI / TinyML cho thiết bị di động (Google TensorFlow Lite).

Vector Đặc trưng Hành vi 6 Chiều (Input Features X in R^6):
- x1: Tần suất mở máy ban đêm (Night Unlock Count: 23h00 - 05h00) [Lần]
- x2: Thời lượng phiên liên tục tối đa (Max Continuous Session Duration) [Phút]
- x3: Tỷ lệ thời gian giải trí / học tập (Entertainment Apps Ratio: Game + Social / Total) [0.0 - 1.0]
- x4: Tốc độ nhảy ứng dụng (Context Switching Velocity / Attention Fragmentation) [Lần/giờ]
- x5: Tần suất dùng máy trong giờ học (School Hours Usage Rate: 07h-11h30, 13h30-17h) [Phút]
- x6: Xu hướng biến thiên thời gian sử dụng 7 ngày (7-Day Usage Velocity Slope) [-1.0 đến +1.0]

Đầu ra (Output Classes Y in {0, 1, 2}):
- 0: BALANCED (Lành mạnh, cân bằng số tốt)
- 1: WARNING (Có dấu hiệu mất tập trung & dùng máy quá độ)
- 2: HIGH_RISK (Nguy cơ nghiện số cao, cần can thiệp sư phạm tích cực)
=============================================================================
"""

import json
import math
import os
import random
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def generate_synthetic_dataset(n_samples=1200, seed=42):
    """
    Sinh tập dữ liệu mẫu chuẩn hóa dựa trên phân phối thực nghiệm của học sinh THCS/THPT.
    Được chia theo 3 nhóm hành vi:
    - 50% Lành mạnh (Balanced)
    - 30% Cảnh báo (Warning)
    - 20% Nguy cơ cao (High Risk)
    """
    random.seed(seed)
    dataset = []
    
    for _ in range(n_samples):
        rand_type = random.random()
        
        if rand_type < 0.50:
            # Nhóm 0: Lành mạnh (Balanced)
            x1 = max(0, int(random.gauss(0.4, 0.6)))      # Ban đêm hầu như không mở máy
            x2 = max(5, random.gauss(25.0, 10.0))         # Phiên dài nhất khoảng 15-35 phút
            x3 = min(1.0, max(0.0, random.gauss(0.25, 0.10))) # Giải trí chiếm < 35%
            x4 = max(2, random.gauss(8.0, 4.0))           # Ít chuyển đổi app lung tung
            x5 = max(0, random.gauss(5.0, 8.0))           # Giờ học hầu như không dùng điện thoại
            x6 = random.gauss(0.0, 0.15)                  # Thời gian ổn định qua các tuần
            label = 0
        elif rand_type < 0.80:
            # Nhóm 1: Cảnh báo (Warning)
            x1 = max(1, int(random.gauss(2.5, 1.2)))      # Thỉnh thoảng thức đêm lướt điện thoại
            x2 = max(30, random.gauss(65.0, 15.0))        # Phiên dài 45-80 phút liên tục
            x3 = min(1.0, max(0.1, random.gauss(0.55, 0.12))) # Giải trí chiếm 45-65%
            x4 = max(10, random.gauss(24.0, 7.0))         # Nhảy app khá nhiều
            x5 = max(10, random.gauss(35.0, 15.0))        # Thỉnh thoảng xem trộm máy trong giờ học
            x6 = random.gauss(0.25, 0.18)                 # Có xu hướng tăng thời gian dùng
            label = 1
        else:
            # Nhóm 2: Nguy cơ cao (High Risk)
            x1 = max(3, int(random.gauss(5.5, 1.8)))      # Thường xuyên cày đêm sau 23h
            x2 = max(70, random.gauss(130.0, 30.0))       # Phiên cày game liên tục > 2 tiếng
            x3 = min(1.0, max(0.5, random.gauss(0.82, 0.08))) # Giải trí > 75%
            x4 = max(20, random.gauss(42.0, 10.0))        # Mất tập trung nghiêm trọng
            x5 = max(30, random.gauss(75.0, 25.0))        # Dùng máy nhiều trong giờ học
            x6 = random.gauss(0.55, 0.20)                 # Tăng vọt thời gian dùng
            label = 2
            
        dataset.append(([float(x1), float(x2), float(x3), float(x4), float(x5), float(x6)], label))
        
    random.shuffle(dataset)
    return dataset

def normalize_features(features):
    """
    Min-Max Scaling cho 6 biến số đầu vào:
    Scale bounds:
    x1: [0, 10]
    x2: [0, 180]
    x3: [0.0, 1.0]
    x4: [0, 60]
    x5: [0, 120]
    x6: [-1.0, 1.0] -> [0.0, 1.0]
    """
    x1, x2, x3, x4, x5, x6 = features
    n1 = max(0.0, min(1.0, x1 / 10.0))
    n2 = max(0.0, min(1.0, x2 / 180.0))
    n3 = max(0.0, min(1.0, x3))
    n4 = max(0.0, min(1.0, x4 / 60.0))
    n5 = max(0.0, min(1.0, x5 / 120.0))
    n6 = max(0.0, min(1.0, (x6 + 1.0) / 2.0))
    return [n1, n2, n3, n4, n5, n6]

def relu(x):
    return [max(0.0, val) for val in x]

def softmax(x):
    max_x = max(x)
    exp_x = [math.exp(val - max_x) for val in x]
    sum_exp = sum(exp_x)
    return [val / sum_exp for val in exp_x]

def matmul_add(weights, biases, x):
    """
    weights: shape [output_dim][input_dim]
    biases: shape [output_dim]
    x: shape [input_dim]
    """
    output = []
    for row, b in zip(weights, biases):
        dot = sum(w * val for w, val in zip(row, x)) + b
        output.append(dot)
    return output

def train_mlp():
    """
    Huấn luyện mạng Multi-Layer Perceptron (MLP: 6 -> 16 -> 8 -> 3).
    Sử dụng thuật toán Backpropagation và Stochastic Gradient Descent.
    """
    print("=" * 70)
    print("🔬 BẮT ĐẦU HUẤN LUYỆN MÔ HÌNH AI DỰ BÁO NGUY CƠ NGHIỆN SỐ (CVA-SMARTGUARDIAN)")
    print("=" * 70)
    
    raw_data = generate_synthetic_dataset(n_samples=1500, seed=123)
    train_data = raw_data[:1200]
    test_data = raw_data[1200:]
    
    # Khởi tạo trọng số Xavier
    random.seed(42)
    def init_matrix(rows, cols):
        scale = math.sqrt(2.0 / (rows + cols))
        return [[random.gauss(0.0, scale) for _ in range(cols)] for _ in range(rows)]
        
    W1 = init_matrix(16, 6)
    B1 = [0.0] * 16
    W2 = init_matrix(8, 16)
    B2 = [0.0] * 8
    W3 = init_matrix(3, 8)
    B3 = [0.0] * 3
    
    lr = 0.04
    epochs = 45
    
    for epoch in range(epochs):
        total_loss = 0.0
        correct = 0
        
        for raw_x, y in train_data:
            x = normalize_features(raw_x)
            
            # Forward pass
            z1 = matmul_add(W1, B1, x)
            a1 = relu(z1)
            
            z2 = matmul_add(W2, B2, a1)
            a2 = relu(z2)
            
            z3 = matmul_add(W3, B3, a2)
            probs = softmax(z3)
            
            # Cross-Entropy Loss
            loss = -math.log(max(probs[y], 1e-12))
            total_loss += loss
            
            pred = probs.index(max(probs))
            if pred == y:
                correct += 1
                
            # Backpropagation
            dz3 = [p - (1.0 if idx == y else 0.0) for idx, p in enumerate(probs)]
            
            # Gradients Layer 3
            dW3 = [[dz3[i] * a2[j] for j in range(8)] for i in range(3)]
            dB3 = list(dz3)
            
            # Layer 2 Error
            da2 = [sum(W3[i][j] * dz3[i] for i in range(3)) for j in range(8)]
            dz2 = [da2[j] * (1.0 if z2[j] > 0 else 0.0) for j in range(8)]
            
            dW2 = [[dz2[i] * a1[j] for j in range(16)] for i in range(8)]
            dB2 = list(dz2)
            
            # Layer 1 Error
            da1 = [sum(W2[i][j] * dz2[i] for i in range(8)) for j in range(16)]
            dz1 = [da1[j] * (1.0 if z1[j] > 0 else 0.0) for j in range(16)]
            
            dW1 = [[dz1[i] * x[j] for j in range(6)] for i in range(16)]
            dB1 = list(dz1)
            
            # Update weights
            for i in range(3):
                B3[i] -= lr * dB3[i]
                for j in range(8):
                    W3[i][j] -= lr * dW3[i][j]
                    
            for i in range(8):
                B2[i] -= lr * dB2[i]
                for j in range(16):
                    W2[i][j] -= lr * dW2[i][j]
                    
            for i in range(16):
                B1[i] -= lr * dB1[i]
                for j in range(6):
                    W1[i][j] -= lr * dW1[i][j]
                    
        if (epoch + 1) % 10 == 0 or epoch == epochs - 1:
            acc = correct / len(train_data) * 100.0
            avg_loss = total_loss / len(train_data)
            print(f"Epoch {epoch+1:02d}/{epochs:02d} - Loss: {avg_loss:.4f} - Training Accuracy: {acc:.2f}%")
            
    # Đánh giá trên tập kiểm thử (Test Set)
    test_correct = 0
    confusion_matrix = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
    
    for raw_x, y in test_data:
        x = normalize_features(raw_x)
        z1 = matmul_add(W1, B1, x)
        a1 = relu(z1)
        z2 = matmul_add(W2, B2, a1)
        a2 = relu(z2)
        z3 = matmul_add(W3, B3, a2)
        probs = softmax(z3)
        pred = probs.index(max(probs))
        if pred == y:
            test_correct += 1
        confusion_matrix[y][pred] += 1
        
    test_accuracy = test_correct / len(test_data) * 100.0
    print("\n" + "=" * 70)
    print(f"🎯 KẾT QUẢ ĐÁNH GIÁ TRÊN TẬP KIỂM THỬ ĐỘC LẬP: {test_accuracy:.2f}%")
    print("=" * 70)
    print("Ma Trận Nhầm Lẫn (Confusion Matrix):")
    print("               Dự Báo: Lành Mạnh | Cảnh Báo | Nguy Cơ Cao")
    print(f"Thực Tế Lành Mạnh (0): {confusion_matrix[0][0]:10d} | {confusion_matrix[0][1]:8d} | {confusion_matrix[0][2]:11d}")
    print(f"Thực Tế Cảnh Báo  (1): {confusion_matrix[1][0]:10d} | {confusion_matrix[1][1]:8d} | {confusion_matrix[1][2]:11d}")
    print(f"Thực Tế Nguy Cơ   (2): {confusion_matrix[2][0]:10d} | {confusion_matrix[2][1]:8d} | {confusion_matrix[2][2]:11d}")
    
    # Xuất file trọng số JSON
    weights_data = {
        "model_architecture": "MLP-6-16-8-3",
        "scientific_reference": "Kwon et al., PLOS ONE (2013) SAS-SV",
        "test_accuracy": test_accuracy,
        "input_features": [
            "night_unlocks",
            "max_continuous_minutes",
            "entertainment_ratio",
            "switching_velocity",
            "school_hours_minutes",
            "velocity_slope_7d"
        ],
        "normalization_bounds": {
            "x1_max": 10.0,
            "x2_max": 180.0,
            "x3_max": 1.0,
            "x4_max": 60.0,
            "x5_max": 120.0,
            "x6_min": -1.0,
            "x6_max": 1.0
        },
        "weights": {
            "W1": W1,
            "B1": B1,
            "W2": W2,
            "B2": B2,
            "W3": W3,
            "B3": B3
        }
    }
    
    output_dir = os.path.dirname(os.path.abspath(__file__))
    output_path = os.path.join(output_dir, "weights_config.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(weights_data, f, indent=2, ensure_ascii=False)
        
    print(f"\n[OK] Đã xuất thành công tệp cấu hình trọng số AI: {output_path}")

if __name__ == "__main__":
    train_mlp()
